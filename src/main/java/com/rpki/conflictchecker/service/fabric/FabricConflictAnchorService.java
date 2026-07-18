package com.rpki.conflictchecker.service.fabric;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.rpki.conflictchecker.dto.ConflictResult;
import com.rpki.conflictchecker.dto.fabric.ChaincodeConflictDetectResult;
import com.rpki.conflictchecker.dto.fabric.CertificateRedactedPayload;
import com.rpki.conflictchecker.dto.fabric.ConflictEvidencePayload;
import com.rpki.conflictchecker.dto.fabric.FabricChaincodeTxResponse;
import com.rpki.conflictchecker.entity.ConflictRecord;
import com.rpki.conflictchecker.entity.FabricTxRecord;
import com.rpki.conflictchecker.entity.RpkiCert;
import com.rpki.conflictchecker.mapper.ConflictRecordMapper;
import com.rpki.conflictchecker.mapper.RpkiCertMapper;
import com.rpki.conflictchecker.service.StorageService;
import com.rpki.conflictchecker.util.ConflictKeyBuilder;
import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 链下检测完成后：批量写入脱敏证书 + 批量冲突存证（优先 {@code recordConflictEvidenceBatch}），
 * 并回写 {@link RpkiCert} / {@link ConflictRecord} / {@link FabricTxRecord}。
 *
 * <p><b>事务一致性（开题说明）</b>：Fabric 提交与 MySQL 无法单 ACID 事务。本实现采用「双阶段结果 + 可观测失败」：</p>
 * <ul>
 *   <li>仅当「证书批次」与「冲突批次」均成功时，才将 {@code is_sent_to_fabric=true} 写回业务表，并清除
 *       {@code fabric_send_failed}。</li>
 *   <li>任一步在重试耗尽后仍失败：业务表标记 {@code fabric_send_failed=true}、{@code fabric_last_error}；
 *       链下冲突结论 {@code has_conflict} 保持检测阶段结果（通常为 true），不因上链失败而被抹掉。</li>
 *   <li>{@link FabricTxRecord} 记录每次尝试与阶段（{@code pipeline_step}、{@code correlation_id}），满足可审计追溯。</li>
 * </ul>
 */
@Slf4j
@Service
public class FabricConflictAnchorService {

    private static final Gson GSON = new Gson();

    private static final int ERR_MSG_MAX = 2000;

    @Autowired
    private FabricBlockchainFacade fabricBlockchainFacade;

    @Autowired
    private RpkiCertMapper rpkiCertMapper;

    @Autowired
    private ConflictRecordMapper conflictRecordMapper;

    @Autowired
    private StorageService storageService;

    @Value("${fabric.anchor.auto-after-detect:false}")
    private boolean autoAfterDetect;

    @Value("${fabric.anchor.mock-persist-db:true}")
    private boolean mockPersistDb;

    @Value("${fabric.anchor.max-conflicts-per-run:500}")
    private int maxConflictsPerRun;

    @Value("${fabric.anchor.details-short-max:512}")
    private int detailsShortMax;

    /** 证书批次 / 冲突批次提交失败时的最大重试次数（含首次，共 retryMax 次尝试） */
    @Value("${fabric.anchor.retry-max:3}")
    private int retryMax;

    @Value("${fabric.anchor.retry-delay-ms:400}")
    private long retryDelayMs;

    public boolean isAutoAnchorEnabled() {
        return autoAfterDetect;
    }

    /**
     * 检测流水线尾部调用：仅在开启 {@code fabric.anchor.auto-after-detect} 时执行。
     */
    public void anchorAfterDetectionIfEnabled(List<ConflictResult> results, Map<Long, RpkiCert> certsById) {
        if (!autoAfterDetect) {
            return;
        }
        anchorAfterDetection(results, certsById);
    }

    /**
     * 将本轮冲突涉及的证书与冲突摘要提交到账本，并回写数据库（含重试与失败补偿标记）。
     */
    public AnchorSummary anchorAfterDetection(List<ConflictResult> results, Map<Long, RpkiCert> certsById) {
        if (results == null || results.isEmpty()) {
            return new AnchorSummary(null, null, 0, 0, null);
        }
        List<ConflictResult> slice = results.size() > maxConflictsPerRun
                ? results.subList(0, maxConflictsPerRun)
                : results;
        if (slice.size() < results.size()) {
            log.warn("[Fabric 锚定] 冲突条数超过 fabric.anchor.max-conflicts-per-run={}，仅处理前 {} 条",
                    maxConflictsPerRun, slice.size());
        }

        boolean real = fabricBlockchainFacade.isReal();
        if (!real && !mockPersistDb) {
            log.debug("[Fabric 锚定] MOCK 且 mock-persist-db=false，跳过");
            return new AnchorSummary(null, null, 0, 0, null);
        }

        String correlationId = UUID.randomUUID().toString();
        persistAuditMarker(correlationId, "OFFLINE_DETECT_ANCHOR_START",
                "conflicts=" + slice.size() + " real=" + real);

        Set<Long> involvedCertIds = new LinkedHashSet<>();
        for (ConflictResult r : slice) {
            if (r.getCertIdA() != null) {
                involvedCertIds.add(r.getCertIdA());
            }
            if (r.getCertIdB() != null) {
                involvedCertIds.add(r.getCertIdB());
            }
        }

        List<CertificateRedactedPayload> certPayloads = new ArrayList<>();
        Set<String> seenHash = new LinkedHashSet<>();
        for (Long id : involvedCertIds) {
            RpkiCert c = certsById != null ? certsById.get(id) : null;
            if (c == null) {
                c = rpkiCertMapper.selectById(id);
            }
            if (c == null || c.getCertHash() == null || c.getCertHash().isBlank()) {
                continue;
            }
            if (!seenHash.add(c.getCertHash().toLowerCase(Locale.ROOT))) {
                continue;
            }
            certPayloads.add(toRedactedPayload(c));
        }

        List<ConflictEvidencePayload> evidencePayloads = new ArrayList<>();
        for (ConflictResult r : slice) {
            RpkiCert ca = resolveCert(r.getCertIdA(), certsById);
            RpkiCert cb = resolveCert(r.getCertIdB(), certsById);
            if (ca == null || cb == null
                    || ca.getCertHash() == null || ca.getCertHash().isBlank()
                    || cb.getCertHash() == null || cb.getCertHash().isBlank()) {
                log.warn("[Fabric 锚定] 跳过无 cert_hash 的冲突对 certIdA={} certIdB={}", r.getCertIdA(), r.getCertIdB());
                continue;
            }
            String key = ConflictKeyBuilder.toLedgerKey(r);
            String ha = ca.getCertHash().trim();
            String hb = cb.getCertHash().trim();
            String orderedA = ha.compareToIgnoreCase(hb) <= 0 ? ha : hb;
            String orderedB = ha.compareToIgnoreCase(hb) <= 0 ? hb : ha;
            evidencePayloads.add(ConflictEvidencePayload.builder()
                    .conflictKey(key)
                    .conflictId(key)
                    .certHashA(orderedA)
                    .certHashB(orderedB)
                    .conflictType(r.getConflictType())
                    .severity(r.getSeverity() != null ? r.getSeverity() : "MEDIUM")
                    .ruleReference(r.getRuleReference())
                    .detailsShort(truncate(r.getDetails(), detailsShortMax))
                    .detectedAt(System.currentTimeMillis())
                    .build());
        }

        if (evidencePayloads.isEmpty()) {
            log.warn("[Fabric 锚定] 无有效冲突存证载荷，终止");
            return new AnchorSummary(null, null, 0, 0, correlationId);
        }

        FabricSubmitResult certResult = null;
        String certBatchTxId = null;
        if (!certPayloads.isEmpty()) {
            FabricSubmitOutcome certOut = submitWithRetry(
                    () -> fabricBlockchainFacade.submitCertificateBatchToFabric(certPayloads),
                    "CERT_BATCH", correlationId);
            if (!certOut.success()) {
                log.error("[Fabric 锚定] 证书批次在 {} 次尝试后仍失败 correlationId={}: {}",
                        retryMax, correlationId, certOut.errorMessage());
                persistFabricTx(null, certOut.lastResult(), "CERT_BATCH", "FAILED",
                        certOut.errorMessage(), correlationId);
                markCertIdsSendFailed(involvedCertIds, certOut.errorMessage());
                markConflictKeysSendFailed(keysFromEvidence(evidencePayloads), certOut.errorMessage());
                return new AnchorSummary(null, null, 0, 0, correlationId);
            }
            certResult = certOut.result();
            certBatchTxId = certResult.txId();
            persistFabricTx(null, certResult, "CERT_BATCH", "SUCCESS", null, correlationId);
        }

        FabricSubmitOutcome conflictOut = submitWithRetry(
                () -> fabricBlockchainFacade.submitConflictBatchToFabric(evidencePayloads),
                "CONFLICT_BATCH", correlationId);
        if (!conflictOut.success()) {
            log.error("[Fabric 锚定] 冲突存证批次在 {} 次尝试后仍失败 correlationId={}: {}",
                    retryMax, correlationId, conflictOut.errorMessage());
            persistFabricTx(null, conflictOut.lastResult(), "CONFLICT_BATCH", "FAILED",
                    conflictOut.errorMessage(), correlationId);
            markCertIdsSendFailed(involvedCertIds, conflictOut.errorMessage());
            markConflictKeysSendFailed(keysFromEvidence(evidencePayloads), conflictOut.errorMessage());
            return new AnchorSummary(certBatchTxId, null, 0, 0, correlationId);
        }

        FabricSubmitResult conflictResult = conflictOut.result();
        String conflictBatchTxId = conflictResult.txId();
        persistFabricTx(null, conflictResult, "CONFLICT_BATCH", "SUCCESS", null, correlationId);

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        String finalTx = conflictResult.txId() != null ? conflictResult.txId()
                : certResult != null ? certResult.txId() : null;

        Map<Long, String> detailsByCertId = aggregateConflictDetailsByCertId(slice);

        for (Long cid : involvedCertIds) {
            String agg = detailsByCertId.get(cid);
            rpkiCertMapper.update(null, new LambdaUpdateWrapper<RpkiCert>()
                    .set(RpkiCert::getFabricTxId, finalTx)
                    .set(RpkiCert::getIsSentToFabric, true)
                    .set(RpkiCert::getFabricSendTime, now)
                    .set(RpkiCert::getFabricSendFailed, false)
                    .set(RpkiCert::getFabricLastError, null)
                    .set(RpkiCert::getHasConflict, true)
                    .set(agg != null, RpkiCert::getConflictDetails, truncate(agg, 65000))
                    .eq(RpkiCert::getId, cid));
        }

        for (ConflictEvidencePayload ep : evidencePayloads) {
            conflictRecordMapper.update(null, new LambdaUpdateWrapper<ConflictRecord>()
                    .set(ConflictRecord::getFabricTxId, finalTx)
                    .set(ConflictRecord::getIsSentToFabric, true)
                    .set(ConflictRecord::getFabricSendTime, now)
                    .set(ConflictRecord::getFabricSendFailed, false)
                    .set(ConflictRecord::getFabricLastError, null)
                    .eq(ConflictRecord::getConflictKey, ep.getConflictKey()));
        }

        persistAuditMarker(correlationId, "OFFLINE_DETECT_ANCHOR_DONE",
                "certTx=" + certBatchTxId + " conflictTx=" + conflictBatchTxId);

        return new AnchorSummary(certBatchTxId, conflictBatchTxId, involvedCertIds.size(), evidencePayloads.size(),
                correlationId);
    }

    /**
     * 链上批量检测返回 JSON 之后：解析 findings，调用 {@code recordConflictEvidenceBatch} 存证并重试，
     * 再按 cert_hash 回写 {@link RpkiCert}（含 {@code has_conflict}、{@code conflict_details}、失败标记）。
     */
    public FabricSubmitResult anchorAfterChainDetectResult(ChaincodeConflictDetectResult r) {
        if (r == null) {
            return null;
        }
        boolean real = fabricBlockchainFacade.isReal();
        if (!real && !mockPersistDb) {
            log.debug("[Fabric 锚定/链上检测后续] MOCK 且 mock-persist-db=false，跳过");
            return null;
        }

        String correlationId = UUID.randomUUID().toString();
        persistAuditMarker(correlationId, "CHAIN_DETECT_ANCHOR_START",
                "verdict=" + r.getVerdict() + " detectTxId=" + r.getTxId());

        List<ConflictEvidencePayload> payloads = buildEvidencePayloadsFromChainDetect(r);
        if (payloads.isEmpty()) {
            log.info("[Fabric 锚定/链上检测后续] 无可构建的存证载荷（无 findings 或缺 cert_hash），跳过 recordConflictEvidenceBatch");
            return null;
        }

        FabricSubmitOutcome out = submitWithRetry(
                () -> fabricBlockchainFacade.submitConflictBatchToFabric(payloads),
                "CHAIN_DETECT_CONFLICT_BATCH", correlationId);
        if (!out.success()) {
            log.error("[Fabric 锚定/链上检测后续] 存证失败 correlationId={}: {}", correlationId, out.errorMessage());
            persistFabricTx(null, out.lastResult(), "CHAIN_DETECT_CONFLICT_BATCH", "FAILED",
                    out.errorMessage(), correlationId);
            markCertHashesSendFailed(collectCertHashesFromPayloads(payloads), out.errorMessage());
            markConflictKeysSendFailed(keysFromEvidence(payloads), out.errorMessage());
            return null;
        }

        FabricSubmitResult result = out.result();
        persistFabricTx(null, result, "CHAIN_DETECT_CONFLICT_BATCH", "SUCCESS", null, correlationId);

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        String txId = result.txId();
        Map<String, String> detailsByHash = aggregateDetailsByCertHashFromPayloads(payloads);
        Set<String> hashes = collectCertHashesFromPayloads(payloads);

        for (String h : hashes) {
            String detail = detailsByHash.getOrDefault(h.toLowerCase(Locale.ROOT), "");
            applySuccessfulAnchorByCertHash(h, txId, now, detail);
        }

        for (ConflictEvidencePayload ep : payloads) {
            conflictRecordMapper.update(null, new LambdaUpdateWrapper<ConflictRecord>()
                    .set(ConflictRecord::getFabricTxId, txId)
                    .set(ConflictRecord::getIsSentToFabric, true)
                    .set(ConflictRecord::getFabricSendTime, now)
                    .set(ConflictRecord::getFabricSendFailed, false)
                    .set(ConflictRecord::getFabricLastError, null)
                    .eq(ConflictRecord::getConflictKey, ep.getConflictKey()));
        }

        persistAuditMarker(correlationId, "CHAIN_DETECT_ANCHOR_DONE", "evidenceTxId=" + txId);
        return result;
    }

    /**
     * 仅提交证书批次（REST 手动触发）。
     */
    public FabricSubmitResult submitCertificatesOnly(List<CertificateRedactedPayload> batch) {
        try {
            FabricSubmitResult r = fabricBlockchainFacade.submitCertificateBatchToFabric(batch);
            persistFabricTx(null, r, "CERT_BATCH_MANUAL", "SUCCESS", null, null);
            return r;
        } catch (RuntimeException e) {
            persistFabricTx(null, null, "CERT_BATCH_MANUAL", "FAILED", e.getMessage(), null);
            throw e;
        }
    }

    /**
     * 按数据库主键拉取两证并 {@link #submitCertificatesOnly}，供链上 detect 前消除 {@code MISSING_ASSET}。
     */
    public FabricSubmitResult submitCertificatesForCertPair(Long certIdA, Long certIdB) {
        if (certIdA == null || certIdB == null) {
            throw new IllegalArgumentException("certIdA 与 certIdB 均不能为空");
        }
        if (certIdA.equals(certIdB)) {
            throw new IllegalArgumentException("两个证书 ID 不能相同");
        }
        RpkiCert a = rpkiCertMapper.selectById(certIdA);
        RpkiCert b = rpkiCertMapper.selectById(certIdB);
        if (a == null || b == null) {
            throw new IllegalArgumentException("证书不存在，请检查 ID");
        }
        if (a.getCertHash() == null || a.getCertHash().isBlank()
                || b.getCertHash() == null || b.getCertHash().isBlank()) {
            throw new IllegalArgumentException("证书缺少 cert_hash，无法写入账本");
        }
        List<CertificateRedactedPayload> batch = List.of(toRedactedPayload(a), toRedactedPayload(b));
        try {
            FabricSubmitResult r = fabricBlockchainFacade.submitCertificateBatchToFabric(batch);
            persistFabricTx(null, r, "CERT_PAIR_BEFORE_DETECT", "SUCCESS", null, null);
            return r;
        } catch (RuntimeException e) {
            persistFabricTx(null, null, "CERT_PAIR_BEFORE_DETECT", "FAILED", e.getMessage(), null);
            throw e;
        }
    }

    /**
     * 仅提交单条冲突存证（REST 手动触发）。
     */
    public FabricSubmitResult submitConflictOnly(ConflictEvidencePayload payload) {
        try {
            FabricSubmitResult r = fabricBlockchainFacade.submitConflictToFabric(payload);
            persistFabricTx(null, r, "CONFLICT_SINGLE_MANUAL", "SUCCESS", null, null);
            return r;
        } catch (RuntimeException e) {
            persistFabricTx(null, null, "CONFLICT_SINGLE_MANUAL", "FAILED", e.getMessage(), null);
            throw e;
        }
    }

    /**
     * 仅提交批量冲突存证（REST 手动触发）。
     */
    public FabricSubmitResult submitConflictBatchOnly(List<ConflictEvidencePayload> batch) {
        try {
            FabricSubmitResult r = fabricBlockchainFacade.submitConflictBatchToFabric(batch);
            persistFabricTx(null, r, "CONFLICT_BATCH_MANUAL", "SUCCESS", null, null);
            return r;
        } catch (RuntimeException e) {
            persistFabricTx(null, null, "CONFLICT_BATCH_MANUAL", "FAILED", e.getMessage(), null);
            throw e;
        }
    }

    private RpkiCert resolveCert(Long id, Map<Long, RpkiCert> byId) {
        if (id == null) {
            return null;
        }
        RpkiCert c = byId != null ? byId.get(id) : null;
        return c != null ? c : rpkiCertMapper.selectById(id);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static String truncateError(String err) {
        if (err == null) {
            return "";
        }
        String t = err.replace("\n", " ").trim();
        return truncate(t, ERR_MSG_MAX);
    }

    private static CertificateRedactedPayload toRedactedPayload(RpkiCert c) {
        return CertificateRedactedPayload.builder()
                .certHash(c.getCertHash())
                .rir(c.getRir())
                .certNameShort(shorten(c.getCertName(), 120))
                .ipv4Prefixes(parseListJson(c.getIpv4Prefixes()))
                .ipv6Prefixes(parseListJson(c.getIpv6Prefixes()))
                .asNumbers(parseListJson(c.getAsNumbers()))
                .parentRef(c.getParentCertId() != null ? String.valueOf(c.getParentCertId()) : null)
                .parentCertId(c.getParentCertId())
                .revoked(Boolean.TRUE.equals(c.getRevoked()))
                .timestamp(System.currentTimeMillis())
                .build();
    }

    private static String shorten(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static List<String> parseListJson(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<?> raw = GSON.fromJson(json, List.class);
            if (raw == null) {
                return new ArrayList<>();
            }
            return raw.stream().map(Object::toString).collect(Collectors.toList());
        } catch (Exception e) {
            return List.of(json);
        }
    }

    /**
     * 带指数退避的简单重试：保证 transient 网络/背书抖动下的最终稳定性。
     */
    private FabricSubmitOutcome submitWithRetry(Supplier<FabricSubmitResult> submit, String pipelineStep,
                                                String correlationId) {
        RuntimeException last = null;
        FabricSubmitResult lastOk = null;
        for (int attempt = 1; attempt <= retryMax; attempt++) {
            try {
                lastOk = submit.get();
                if (attempt > 1) {
                    log.info("[Fabric 锚定] {} 第 {} 次尝试成功 correlationId={}", pipelineStep, attempt, correlationId);
                }
                return FabricSubmitOutcome.ok(lastOk);
            } catch (RuntimeException e) {
                last = e;
                log.warn("[Fabric 锚定] {} 第 {}/{} 次失败 correlationId={}: {}",
                        pipelineStep, attempt, retryMax, correlationId, e.getMessage());
                if (attempt < retryMax) {
                    sleepBackoff(attempt);
                }
            }
        }
        return FabricSubmitOutcome.failed(last, lastOk);
    }

    private void sleepBackoff(int attempt) {
        try {
            Thread.sleep(retryDelayMs * attempt);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("[Fabric 锚定] 重试等待被中断");
        }
    }

    private void persistAuditMarker(String correlationId, String pipelineStep, String payload) {
        FabricTxRecord rec = FabricTxRecord.builder()
                .conflictRecordId(null)
                .mode(fabricBlockchainFacade.isReal() ? "REAL" : "MOCK")
                .txId("audit-" + UUID.randomUUID())
                .blockNum(null)
                .payload(payload)
                .status("AUDIT")
                .errorMessage(null)
                .correlationId(correlationId)
                .pipelineStep(pipelineStep)
                .createdAt(LocalDateTime.now())
                .build();
        storageService.saveFabricTx(rec);
    }

    private void persistFabricTx(Long conflictRecordId, FabricSubmitResult result, String pipelineStep, String status,
                                 String err, String correlationId) {
        String txId = result != null ? result.txId() : null;
        if (txId == null && result != null && result.rawResponse() != null) {
            try {
                FabricChaincodeTxResponse parsed = GSON.fromJson(result.rawResponse(), FabricChaincodeTxResponse.class);
                if (parsed != null && parsed.getTxId() != null) {
                    txId = parsed.getTxId();
                }
            } catch (Exception ignored) {
            }
        }
        if (txId == null && "FAILED".equals(status)) {
            txId = "failed-" + UUID.randomUUID();
        }
        String mode = fabricBlockchainFacade.isReal() ? "REAL" : "MOCK";
        FabricTxRecord rec = FabricTxRecord.builder()
                .conflictRecordId(conflictRecordId)
                .mode(mode)
                .txId(txId)
                .blockNum(null)
                .payload(result != null ? result.rawResponse() : pipelineStep)
                .status(status)
                .errorMessage(truncateError(err))
                .correlationId(correlationId)
                .pipelineStep(pipelineStep)
                .createdAt(LocalDateTime.now())
                .build();
        storageService.saveFabricTx(rec);
    }

    private static Map<Long, String> aggregateConflictDetailsByCertId(List<ConflictResult> slice) {
        Map<Long, StringBuilder> tmp = new HashMap<>();
        for (ConflictResult r : slice) {
            String d = r.getDetails() == null ? "" : r.getDetails();
            appendDetail(tmp, r.getCertIdA(), d);
            appendDetail(tmp, r.getCertIdB(), d);
        }
        Map<Long, String> out = new HashMap<>();
        for (Map.Entry<Long, StringBuilder> e : tmp.entrySet()) {
            out.put(e.getKey(), e.getValue().toString().trim());
        }
        return out;
    }

    private static void appendDetail(Map<Long, StringBuilder> map, Long certId, String fragment) {
        if (certId == null || fragment == null || fragment.isBlank()) {
            return;
        }
        map.computeIfAbsent(certId, k -> new StringBuilder())
                .append(fragment.trim()).append("; ");
    }

    private List<ConflictEvidencePayload> buildEvidencePayloadsFromChainDetect(ChaincodeConflictDetectResult r) {
        List<ConflictEvidencePayload> out = new ArrayList<>();
        String detectTx = r.getTxId() != null ? r.getTxId() : "unknown";
        int idx = 0;
        if (r.getFindings() != null) {
            for (ChaincodeConflictDetectResult.FindingEntry fe : r.getFindings()) {
                String ha = fe.getCertHashA();
                String hb = fe.getCertHashB();
                if (ha == null || ha.isBlank() || hb == null || hb.isBlank()) {
                    continue;
                }
                ha = ha.trim();
                hb = hb.trim();
                String orderedA = ha.compareToIgnoreCase(hb) <= 0 ? ha : hb;
                String orderedB = ha.compareToIgnoreCase(hb) <= 0 ? hb : ha;
                String key = ConflictKeyBuilder.toLedgerKey("chain|" + detectTx + "|" + idx + "|" + orderedA + "|" + orderedB);
                String ctype = fe.getType() != null ? fe.getType()
                        : (r.getPrimaryConflictType() != null ? r.getPrimaryConflictType() : "CHAIN_DETECT");
                out.add(ConflictEvidencePayload.builder()
                        .conflictKey(key)
                        .conflictId(key)
                        .certHashA(orderedA)
                        .certHashB(orderedB)
                        .conflictType(ctype)
                        .severity("MEDIUM")
                        .ruleReference(fe.getRulePhase())
                        .detailsShort(truncate(fe.getDetail(), detailsShortMax))
                        .detectedAt(System.currentTimeMillis())
                        .build());
                idx++;
            }
        }
        if (out.isEmpty() && "CONFLICT".equalsIgnoreCase(r.getVerdict())
                && r.getInvolvedCertHashes() != null && r.getInvolvedCertHashes().size() >= 2) {
            List<String> hs = r.getInvolvedCertHashes().stream()
                    .filter(x -> x != null && !x.isBlank())
                    .map(String::trim)
                    .collect(Collectors.toList());
            if (hs.size() >= 2) {
                String ha = hs.get(0);
                String hb = hs.get(1);
                String orderedA = ha.compareToIgnoreCase(hb) <= 0 ? ha : hb;
                String orderedB = ha.compareToIgnoreCase(hb) <= 0 ? hb : ha;
                String key = ConflictKeyBuilder.toLedgerKey("chain|fallback|" + detectTx + "|" + orderedA + "|" + orderedB);
                String ctype = r.getPrimaryConflictType() != null ? r.getPrimaryConflictType() : "CHAIN_DETECT";
                out.add(ConflictEvidencePayload.builder()
                        .conflictKey(key)
                        .conflictId(key)
                        .certHashA(orderedA)
                        .certHashB(orderedB)
                        .conflictType(ctype)
                        .severity("MEDIUM")
                        .ruleReference("INVOLVED_HASHES_FALLBACK")
                        .detailsShort(truncate("链上检测 CONFLICT，findings 为空，使用 involvedCertHashes 前两项存证", detailsShortMax))
                        .detectedAt(System.currentTimeMillis())
                        .build());
            }
        }
        return out;
    }

    private static Set<String> collectCertHashesFromPayloads(List<ConflictEvidencePayload> payloads) {
        Set<String> hashes = new LinkedHashSet<>();
        for (ConflictEvidencePayload p : payloads) {
            if (p.getInvolvedCertHashes() != null) {
                for (String x : p.getInvolvedCertHashes()) {
                    if (x != null && !x.isBlank()) {
                        hashes.add(x.trim());
                    }
                }
            }
            if (p.getCertHashA() != null && !p.getCertHashA().isBlank()) {
                hashes.add(p.getCertHashA().trim());
            }
            if (p.getCertHashB() != null && !p.getCertHashB().isBlank()) {
                hashes.add(p.getCertHashB().trim());
            }
        }
        return hashes;
    }

    private static List<String> keysFromEvidence(List<ConflictEvidencePayload> evidencePayloads) {
        List<String> keys = new ArrayList<>();
        for (ConflictEvidencePayload ep : evidencePayloads) {
            if (ep.getConflictKey() != null && !ep.getConflictKey().isBlank()) {
                keys.add(ep.getConflictKey());
            }
        }
        return keys;
    }

    private static Map<String, String> aggregateDetailsByCertHashFromPayloads(List<ConflictEvidencePayload> payloads) {
        Map<String, StringBuilder> tmp = new HashMap<>();
        for (ConflictEvidencePayload p : payloads) {
            String d = p.getDetailsShort() != null ? p.getDetailsShort() : "";
            if (p.getCertHashA() != null && !p.getCertHashA().isBlank()) {
                appendDetailHash(tmp, p.getCertHashA().trim(), d);
            }
            if (p.getCertHashB() != null && !p.getCertHashB().isBlank()) {
                appendDetailHash(tmp, p.getCertHashB().trim(), d);
            }
        }
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, StringBuilder> e : tmp.entrySet()) {
            out.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue().toString().trim());
        }
        return out;
    }

    private static void appendDetailHash(Map<String, StringBuilder> map, String hash, String fragment) {
        if (hash == null || fragment == null || fragment.isBlank()) {
            return;
        }
        String k = hash.toLowerCase(Locale.ROOT);
        map.computeIfAbsent(k, x -> new StringBuilder()).append(fragment.trim()).append("; ");
    }

    private void applySuccessfulAnchorByCertHash(String certHash, String txId, LocalDateTime now, String conflictDetails) {
        String h = certHash.trim();
        String lower = h.toLowerCase(Locale.ROOT);
        int n = rpkiCertMapper.update(null, buildCertHashSuccessWrapper(txId, now, conflictDetails)
                .eq(RpkiCert::getCertHash, h));
        if (n == 0) {
            rpkiCertMapper.update(null, buildCertHashSuccessWrapper(txId, now, conflictDetails)
                    .eq(RpkiCert::getCertHash, lower));
        }
    }

    private static LambdaUpdateWrapper<RpkiCert> buildCertHashSuccessWrapper(String txId, LocalDateTime now,
                                                                              String conflictDetails) {
        LambdaUpdateWrapper<RpkiCert> w = new LambdaUpdateWrapper<RpkiCert>()
                .set(RpkiCert::getFabricTxId, txId)
                .set(RpkiCert::getIsSentToFabric, true)
                .set(RpkiCert::getFabricSendTime, now)
                .set(RpkiCert::getFabricSendFailed, false)
                .set(RpkiCert::getFabricLastError, null)
                .set(RpkiCert::getHasConflict, true);
        if (conflictDetails != null && !conflictDetails.isBlank()) {
            w.set(RpkiCert::getConflictDetails, truncate(conflictDetails, 65000));
        }
        return w;
    }

    private void markCertIdsSendFailed(Set<Long> certIds, String err) {
        String msg = truncateError(err);
        for (Long id : certIds) {
            if (id == null) {
                continue;
            }
            rpkiCertMapper.update(null, new LambdaUpdateWrapper<RpkiCert>()
                    .set(RpkiCert::getFabricSendFailed, true)
                    .set(RpkiCert::getFabricLastError, msg)
                    .set(RpkiCert::getIsSentToFabric, false)
                    .eq(RpkiCert::getId, id));
        }
    }

    private void markCertHashesSendFailed(Set<String> hashes, String err) {
        String msg = truncateError(err);
        for (String h : hashes) {
            if (h == null || h.isBlank()) {
                continue;
            }
            String t = h.trim();
            String lower = t.toLowerCase(Locale.ROOT);
            int n = rpkiCertMapper.update(null, new LambdaUpdateWrapper<RpkiCert>()
                    .set(RpkiCert::getFabricSendFailed, true)
                    .set(RpkiCert::getFabricLastError, msg)
                    .set(RpkiCert::getIsSentToFabric, false)
                    .eq(RpkiCert::getCertHash, t));
            if (n == 0) {
                rpkiCertMapper.update(null, new LambdaUpdateWrapper<RpkiCert>()
                        .set(RpkiCert::getFabricSendFailed, true)
                        .set(RpkiCert::getFabricLastError, msg)
                        .set(RpkiCert::getIsSentToFabric, false)
                        .eq(RpkiCert::getCertHash, lower));
            }
        }
    }

    private void markConflictKeysSendFailed(List<String> keys, String err) {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        String msg = truncateError(err);
        for (String k : keys) {
            if (k == null || k.isBlank()) {
                continue;
            }
            conflictRecordMapper.update(null, new LambdaUpdateWrapper<ConflictRecord>()
                    .set(ConflictRecord::getFabricSendFailed, true)
                    .set(ConflictRecord::getFabricLastError, msg)
                    .set(ConflictRecord::getIsSentToFabric, false)
                    .eq(ConflictRecord::getConflictKey, k));
        }
    }

    /**
     * 锚定摘要：{@code correlationId} 用于在 {@code fabric_tx_record} 中串联审计记录。
     */
    public record AnchorSummary(String certBatchTxId, String conflictBatchTxId, int updatedCerts, int updatedConflicts,
                                String correlationId) {
    }

    private record FabricSubmitOutcome(FabricSubmitResult result, RuntimeException failure, FabricSubmitResult lastResult) {
        static FabricSubmitOutcome ok(FabricSubmitResult r) {
            return new FabricSubmitOutcome(r, null, r);
        }

        static FabricSubmitOutcome failed(RuntimeException failure, FabricSubmitResult lastResult) {
            return new FabricSubmitOutcome(null, failure, lastResult);
        }

        boolean success() {
            return result != null;
        }

        String errorMessage() {
            return failure != null ? failure.getMessage() : "unknown";
        }
    }
}
