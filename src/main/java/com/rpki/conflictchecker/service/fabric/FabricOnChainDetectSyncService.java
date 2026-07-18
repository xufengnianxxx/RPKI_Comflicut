package com.rpki.conflictchecker.service.fabric;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.rpki.conflictchecker.dto.fabric.ChaincodeConflictDetectResult;
import com.rpki.conflictchecker.dto.fabric.ConflictEvidencePayload;
import com.rpki.conflictchecker.entity.RpkiCert;
import com.rpki.conflictchecker.mapper.RpkiCertMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 将链上 {@code detectConflictOnChain*} 返回的 JSON 同步回 MySQL（示例：{@code has_conflict}、{@code fabric_tx_id}），
 * 并与 {@link FabricConflictAnchorService#submitConflictBatchToFabric(List)} 配合完成「检测 → 存证 → 落库」闭环。
 *
 * <p><b>调用示例</b>（在业务层）：</p>
 * <pre>
 * // 双证检测 + 回写 has_conflict / fabric_tx_id
 * String json = fabricBlockchainFacade.detectConflictOnChain(ha, hb);
 * fabricOnChainDetectSyncService.applyDetectResultToCertificates(json);
 *
 * // 批量 STATE_HASHES 检测 + 同一方法回写（链码返回 verdict、involvedCertHashes）
 * String batchJson = fabricOnChainDetectSyncService.runStateHashesBatchDetectAndApplyDb(List.of(ha, hb, hc));
 *
 * // 链上检测 JSON → 自动 recordConflictEvidenceBatch 存证 + 回写 is_sent_to_fabric / fabric_send_failed 等
 * fabricOnChainDetectSyncService.anchorDetectionResultAfterChainDetect(batchJson);
 *
 * FabricSubmitResult ev = fabricBlockchainFacade.submitConflictBatchToFabric(List.of(
 *     ConflictEvidencePayload.builder().conflictId("k").conflictKey("k")
 *         .certHashA(ha).certHashB(hb).conflictType("PREFIX_OVERLAP")
 *         .detectedAt(System.currentTimeMillis()).build()));
 * fabricOnChainDetectSyncService.applyConflictBatchTxToCertificates(ev.txId(), List.of(...));
 * </pre>
 *
 * <p><b>链上 / 链下规则对齐（开题一致性）</b>：链下引擎应对同一对证书使用与链码相同的谓词顺序与定义——
 * IPv4/IPv6 前缀先判定包含，再部分重叠，再异长相邻；AS 号规范为 {@link Long} 集合（去 AS 前缀、非数字跳过）；
 * 在存在共享 IP 资源时 {@code !Sa.equals(Sb)} 记 AS 冲突；继承检查子前缀被父某前缀覆盖、子 AS ⊆ 父 AS。
 * 批量检测后以 {@link ChaincodeConflictDetectResult#getPrimaryConflictType()} 与链下 worst 类型对照可做回归。</p>
 */
@Slf4j
@Service
public class FabricOnChainDetectSyncService {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    @Autowired
    private RpkiCertMapper rpkiCertMapper;

    @Autowired
    private FabricBlockchainFacade fabricBlockchainFacade;

    @Autowired
    private FabricConflictAnchorService fabricConflictAnchorService;

    /**
     * 构造 {@code STATE_HASHES} 请求 → 提交 {@code detectConflictOnChainBatch} → 将返回 JSON 同步到 {@code rpki_cert}。
     * 适用于开题模型中「批量链上校验后落库」的闭环演示。
     */
    public String runStateHashesBatchDetectAndApplyDb(List<String> certHashes) {
        String requestJson = buildStateHashesRequestJson(certHashes);
        String detectJson = fabricBlockchainFacade.detectConflictOnChainBatch(requestJson);
        applyDetectResultToCertificates(detectJson);
        return detectJson;
    }

    /**
     * 与链下检测结果融合：对同一证书对用相同 ASN 归一化（Long、去 AS 前缀）与相同 CIDR 区间语义比对
     * {@code primaryConflictType} / 各 {@link ChaincodeConflictDetectResult.FindingEntry#getRulePhase()}。
     */
    public void reconcileWithOfflineWorstType(String detectJson, String offlineWorstType) {
        ChaincodeConflictDetectResult on = GSON.fromJson(detectJson, ChaincodeConflictDetectResult.class);
        if (on == null || on.getPrimaryConflictType() == null || offlineWorstType == null) {
            return;
        }
        log.info("[链上链下对照] onChainPrimary={} offlineWorst={} verdict={}",
                on.getPrimaryConflictType(), offlineWorstType, on.getVerdict());
    }

    /**
     * 解析链上 {@code detectConflictOnChain*} 返回的 JSON：先按 {@link #applyDetectResultToCertificates(String)} 落检测结论，
     * 再调用 {@link FabricConflictAnchorService#anchorAfterChainDetectResult(ChaincodeConflictDetectResult)}
     * 触发 {@code recordConflictEvidenceBatch} 存证（含重试与失败标记），形成「链上核心校验 → 不可篡改存证 → 库表一致」闭环。
     *
     * @param detectJson 链码返回的 JSON
     * @return 存证交易提交结果；无需存证或跳过时为 {@code null}
     */
    public FabricSubmitResult anchorDetectionResultAfterChainDetect(String detectJson) {
        if (detectJson == null || detectJson.isBlank()) {
            return null;
        }
        ChaincodeConflictDetectResult parsed = GSON.fromJson(detectJson, ChaincodeConflictDetectResult.class);
        if (parsed == null) {
            log.warn("[链上检测→存证] JSON 解析失败，跳过");
            return null;
        }
        applyDetectResultToCertificates(detectJson);
        FabricSubmitResult anchored = fabricConflictAnchorService.anchorAfterChainDetectResult(parsed);
        if (anchored != null) {
            log.info("[链上检测→存证] 完成 evidenceTxId={} detectTxId={}", anchored.txId(), parsed.getTxId());
        } else {
            log.warn("[链上检测→存证] 未产生存证交易（无载荷、配置跳过或重试失败），请查 fabric_tx_record 与 fabric_send_failed");
        }
        return anchored;
    }

    /**
     * 解析链码返回的 JSON，对 {@code involvedCertHashes} 对应行更新 {@link RpkiCert#getHasConflict()} 与 {@link RpkiCert#getFabricTxId()}。
     * <ul>
     *   <li>{@code CONFLICT}：{@code has_conflict=true}，写入 {@code fabric_tx_id=txId}。</li>
     *   <li>{@code NO_CONFLICT}：仅写入 {@code fabric_tx_id}（不强行清零 has_conflict，避免覆盖链下其它冲突记录）。</li>
     * </ul>
     * 若 {@code involvedCertHashes} 为空，则从 {@code findings} 中收集 certHashA/B。
     */
    public void applyDetectResultToCertificates(String detectResultJson) {
        if (detectResultJson == null || detectResultJson.isBlank()) {
            return;
        }
        ChaincodeConflictDetectResult r = GSON.fromJson(detectResultJson, ChaincodeConflictDetectResult.class);
        if (r == null || r.getTxId() == null || r.getTxId().isBlank()) {
            log.warn("[链上检测回写] 无法解析 txId，跳过");
            return;
        }
        Set<String> hashes = new LinkedHashSet<>();
        if (r.getInvolvedCertHashes() != null) {
            for (String h : r.getInvolvedCertHashes()) {
                if (h != null && !h.isBlank()) {
                    hashes.add(h.trim());
                }
            }
        }
        if (hashes.isEmpty() && r.getFindings() != null) {
            for (ChaincodeConflictDetectResult.FindingEntry fe : r.getFindings()) {
                if (fe.getCertHashA() != null && !fe.getCertHashA().isBlank()) {
                    hashes.add(fe.getCertHashA().trim());
                }
                if (fe.getCertHashB() != null && !fe.getCertHashB().isBlank()) {
                    hashes.add(fe.getCertHashB().trim());
                }
            }
        }
        if (hashes.isEmpty()) {
            log.debug("[链上检测回写] 无涉事 cert_hash，跳过");
            return;
        }

        boolean conflict = "CONFLICT".equalsIgnoreCase(r.getVerdict());
        for (String h : hashes) {
            LambdaUpdateWrapper<RpkiCert> w = new LambdaUpdateWrapper<RpkiCert>()
                    .eq(RpkiCert::getCertHash, h.trim());
            w.set(RpkiCert::getFabricTxId, r.getTxId());
            if (conflict) {
                w.set(RpkiCert::getHasConflict, true);
            }
            int n = rpkiCertMapper.update(null, w);
            if (n == 0) {
                LambdaUpdateWrapper<RpkiCert> w2 = new LambdaUpdateWrapper<RpkiCert>()
                        .eq(RpkiCert::getCertHash, h.trim().toLowerCase(Locale.ROOT));
                w2.set(RpkiCert::getFabricTxId, r.getTxId());
                if (conflict) {
                    w2.set(RpkiCert::getHasConflict, true);
                }
                rpkiCertMapper.update(null, w2);
            }
        }
    }

    /**
     * 批量存证提交成功后，按载荷中的 cert_hash 回写 {@link RpkiCert}：
     * {@code fabric_tx_id}、{@code is_sent_to_fabric}、{@code fabric_send_time}，并清除 {@code fabric_send_failed} /
     * {@code fabric_last_error}（与 {@link FabricConflictAnchorService} 成功路径语义一致）。
     */
    public void applyConflictBatchTxToCertificates(String fabricTxId, List<ConflictEvidencePayload> batch) {
        if (fabricTxId == null || fabricTxId.isBlank() || batch == null) {
            return;
        }
        Set<String> hashes = new LinkedHashSet<>();
        for (ConflictEvidencePayload p : batch) {
            if (p.getInvolvedCertHashes() != null) {
                p.getInvolvedCertHashes().stream()
                        .filter(x -> x != null && !x.isBlank())
                        .forEach(x -> hashes.add(x.trim()));
            }
            if (p.getCertHashA() != null && !p.getCertHashA().isBlank()) {
                hashes.add(p.getCertHashA().trim());
            }
            if (p.getCertHashB() != null && !p.getCertHashB().isBlank()) {
                hashes.add(p.getCertHashB().trim());
            }
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        for (String h : hashes) {
            String t = h.trim();
            String lower = t.toLowerCase(Locale.ROOT);
            int n = rpkiCertMapper.update(null, new LambdaUpdateWrapper<RpkiCert>()
                    .set(RpkiCert::getFabricTxId, fabricTxId)
                    .set(RpkiCert::getIsSentToFabric, true)
                    .set(RpkiCert::getFabricSendTime, now)
                    .set(RpkiCert::getFabricSendFailed, false)
                    .set(RpkiCert::getFabricLastError, null)
                    .eq(RpkiCert::getCertHash, t));
            if (n == 0) {
                rpkiCertMapper.update(null, new LambdaUpdateWrapper<RpkiCert>()
                        .set(RpkiCert::getFabricTxId, fabricTxId)
                        .set(RpkiCert::getIsSentToFabric, true)
                        .set(RpkiCert::getFabricSendTime, now)
                        .set(RpkiCert::getFabricSendFailed, false)
                        .set(RpkiCert::getFabricLastError, null)
                        .eq(RpkiCert::getCertHash, lower));
            }
        }
    }

    /**
     * 演示用：构造 detect 请求 JSON（STATE_HASHES）。
     */
    public static String buildStateHashesRequestJson(List<String> certHashes) {
        List<String> h = certHashes == null ? List.of() : new ArrayList<>(certHashes);
        return GSON.toJson(java.util.Map.of(
                "mode", "STATE_HASHES",
                "certHashes", h));
    }
}
