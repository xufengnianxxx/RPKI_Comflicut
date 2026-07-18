package com.rpki.conflictchecker.controller;

import com.rpki.conflictchecker.dto.DetectPairRequest;
import com.rpki.conflictchecker.dto.Result;
import com.rpki.conflictchecker.dto.fabric.CertificateRedactedPayload;
import com.rpki.conflictchecker.dto.fabric.ConflictEvidencePayload;
import com.rpki.conflictchecker.dto.fabric.FabricSubmitResponseDto;
import com.rpki.conflictchecker.service.fabric.FabricBlockchainFacade;
import com.rpki.conflictchecker.service.fabric.FabricConflictAnchorService;
import com.rpki.conflictchecker.service.fabric.FabricOnChainDetectSyncService;
import com.rpki.conflictchecker.service.fabric.FabricSubmitResult;
import io.swagger.v3.oas.annotations.Operation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 链上查询 + 写操作入口（写操作经 {@link FabricBlockchainFacade}，与 Java 链码对齐）。
 */
@Slf4j
@RestController
@RequestMapping("/fabric/chain")
public class FabricChainController {

    @Autowired
    private FabricBlockchainFacade fabricBlockchainFacade;

    @Autowired
    private FabricConflictAnchorService fabricConflictAnchorService;

    @Autowired
    private FabricOnChainDetectSyncService fabricOnChainDetectSyncService;

    @Operation(summary = "按 cert_hash 查询链上脱敏证书 JSON")
    @GetMapping("/certificate/{certHash}")
    public ResponseEntity<Result<String>> queryCert(@PathVariable String certHash) {
        String json = fabricBlockchainFacade.queryCertificate(certHash);
        return ResponseEntity.ok(Result.ok(json, "OK"));
    }

    @Operation(summary = "列出链上冲突存证（JSON 数组字符串）")
    @GetMapping("/conflicts")
    public ResponseEntity<Result<String>> conflicts() {
        return ResponseEntity.ok(Result.ok(fabricBlockchainFacade.queryConflicts(), "OK"));
    }

    @Operation(summary = "某证书键的世界状态变更历史（txId 列表 JSON）")
    @GetMapping("/audit/{certHash}")
    public ResponseEntity<Result<String>> audit(@PathVariable String certHash) {
        return ResponseEntity.ok(Result.ok(fabricBlockchainFacade.getAuditHistory(certHash), "OK"));
    }

    @Operation(summary = "批量上链脱敏证书（链码 storeCertificateBatch，并记 fabric_tx_record）")
    @PostMapping("/certificates/batch")
    public ResponseEntity<Result<FabricSubmitResponseDto>> submitCertificateBatch(
            @RequestBody List<CertificateRedactedPayload> batch) {
        FabricSubmitResult r = fabricConflictAnchorService.submitCertificatesOnly(batch);
        return ResponseEntity.ok(Result.ok(FabricSubmitResponseDto.from(r), "submitted"));
    }

    @Operation(summary = "按证书 ID 将两证脱敏数据写入账本（链上 detect 前调用，避免 MISSING_ASSET）")
    @PostMapping("/certificates/batch-by-cert-ids")
    public ResponseEntity<Result<FabricSubmitResponseDto>> submitCertificateBatchByCertIds(
            @RequestBody DetectPairRequest request) {
        if (request == null || request.getCertIdA() == null || request.getCertIdB() == null) {
            throw new IllegalArgumentException("certIdA 与 certIdB 均不能为空");
        }
        FabricSubmitResult r = fabricConflictAnchorService.submitCertificatesForCertPair(
                request.getCertIdA(), request.getCertIdB());
        return ResponseEntity.ok(Result.ok(FabricSubmitResponseDto.from(r), "两证已提交上链（storeCertificateBatch）"));
    }

    @Operation(summary = "单条冲突结果上链存证（链码 recordConflictEvidence，并记 fabric_tx_record）")
    @PostMapping("/conflicts/evidence")
    public ResponseEntity<Result<FabricSubmitResponseDto>> submitConflictEvidence(
            @RequestBody ConflictEvidencePayload payload) {
        FabricSubmitResult r = fabricConflictAnchorService.submitConflictOnly(payload);
        return ResponseEntity.ok(Result.ok(FabricSubmitResponseDto.from(r), "submitted"));
    }

    @Operation(summary = "批量冲突上链存证（链码 recordConflictEvidenceBatch，并记 fabric_tx_record）")
    @PostMapping("/conflicts/evidence/batch")
    public ResponseEntity<Result<FabricSubmitResponseDto>> submitConflictEvidenceBatch(
            @RequestBody List<ConflictEvidencePayload> batch) {
        FabricSubmitResult r = fabricConflictAnchorService.submitConflictBatchOnly(batch);
        return ResponseEntity.ok(Result.ok(FabricSubmitResponseDto.from(r), "submitted"));
    }

    @Operation(summary = "链上确定性冲突检测（返回 JSON：verdict、findings、txId 等；需两证已 storeCertificateBatch）")
    @PostMapping("/detect-on-chain")
    public ResponseEntity<Result<String>> detectOnChain(
            @RequestParam String certHashA,
            @RequestParam String certHashB) {
        String json = fabricBlockchainFacade.detectConflictOnChain(certHashA, certHashB);
        return ResponseEntity.ok(Result.ok(json, "OK"));
    }

    @Operation(summary = "链上批量检测（JSON body：mode=STATE_HASHES+certHashes 或 INLINE_ASSETS+certificates）")
    @PostMapping("/detect-on-chain/batch")
    public ResponseEntity<Result<String>> detectOnChainBatch(@RequestBody String requestJson) {
        String json = fabricBlockchainFacade.detectConflictOnChainBatch(requestJson);
        return ResponseEntity.ok(Result.ok(json, "OK"));
    }

    @Operation(summary = "解析上一步 detect 返回的 JSON 并回写 rpki_cert.fabric_tx_id / has_conflict（涉事 cert_hash）")
    @PostMapping("/detect-on-chain/apply-db")
    public ResponseEntity<Result<Void>> applyDetectToDb(@RequestBody String detectResultJson) {
        fabricOnChainDetectSyncService.applyDetectResultToCertificates(detectResultJson);
        return ResponseEntity.ok(Result.ok(null, "updated"));
    }

    @Operation(summary = "链上检测 JSON → 落库结论 + 自动 recordConflictEvidenceBatch 存证（重试/审计/fabric_send_failed）")
    @PostMapping("/detect-on-chain/anchor-db")
    public ResponseEntity<Result<FabricSubmitResponseDto>> anchorDetectToDb(@RequestBody String detectResultJson) {
        FabricSubmitResult r = fabricOnChainDetectSyncService.anchorDetectionResultAfterChainDetect(detectResultJson);
        String msg = r == null ? "检测已应用；未产生存证（无 findings/配置跳过/失败见 fabric_tx_record）" : "检测已应用且存证已提交";
        return ResponseEntity.ok(Result.ok(FabricSubmitResponseDto.from(r), msg));
    }

}
