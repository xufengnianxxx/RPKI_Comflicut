package com.rpki.conflictchecker.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.rpki.conflictchecker.dto.BulkDirectoryImportResult;
import com.rpki.conflictchecker.dto.ConflictResult;
import com.rpki.conflictchecker.dto.DetectConflictRequest;
import com.rpki.conflictchecker.dto.DetectPairRequest;
import com.rpki.conflictchecker.dto.RecordPairDetectionRequest;
import com.rpki.conflictchecker.dto.DownloadExtractOutcome;
import com.rpki.conflictchecker.dto.Result;
import com.rpki.conflictchecker.dto.RpkCertificateDTO;
import com.rpki.conflictchecker.entity.ConflictRecord;
import com.rpki.conflictchecker.entity.PairDetectionRecord;
import com.rpki.conflictchecker.entity.RpkiCert;
import com.rpki.conflictchecker.service.CertificateChainService;
import com.rpki.conflictchecker.service.CertificateDownloadService;
import com.rpki.conflictchecker.service.CertificateParseService;
import com.rpki.conflictchecker.service.ConflictDetectionService;
import com.rpki.conflictchecker.service.FabricService;
import com.rpki.conflictchecker.service.RpkiBulkDirectoryImportService;
import com.rpki.conflictchecker.service.StorageService;
import com.rpki.conflictchecker.util.FileUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/cert")
public class CertController {

    @Autowired
    private CertificateDownloadService downloadService;

    @Autowired
    private CertificateParseService parseService;

    @Autowired
    private ConflictDetectionService conflictDetectionService;

    @Autowired
    private FabricService fabricService;

    @Autowired
    private StorageService storageService;

    @Autowired
    private CertificateChainService certificateChainService;

    @Autowired
    private RpkiBulkDirectoryImportService rpkiBulkDirectoryImportService;

    @Value("${rpki.sync.reparse-local-when-cached:false}")
    private boolean reparseLocalWhenCached;

    @Operation(summary = "下载并解析RPKI仓库（本地已有证书时可跳过网络下载与重复入库）")
    @PostMapping("/download-and-parse")
    public ResponseEntity<Result<Map<String, Object>>> downloadAndParse(
            @Parameter(description = "true=强制从网络重新下载并解压")
            @RequestParam(name = "forceNetwork", defaultValue = "false") boolean forceNetwork,
            @Parameter(description = "true=本次强制从磁盘重新解析并入库（仅在与本地缓存联用时有效）")
            @RequestParam(name = "reparseLocal", defaultValue = "false") boolean reparseLocal
    ) throws Exception {
        DownloadExtractOutcome outcome = downloadService.downloadAndExtractAllRirs(forceNetwork);
        String mode = outcome.getMode();
        boolean fromLocalCache = mode != null && mode.startsWith("local-cache");
        boolean skipParse = fromLocalCache
                && storageService.countCertificates() > 0
                && !reparseLocal
                && !reparseLocalWhenCached;
        if (skipParse) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("skippedParse", true);
            data.put("reason", "数据库已有证书，已跳过磁盘重新解析。冲突检测请用 POST /cert/detect-conflicts；若要重新入库可传 reparseLocal=true 或配置 rpki.sync.reparse-local-when-cached=true；若要重新下载可传 forceNetwork=true。");
            data.put("runMode", mode);
            data.put("dbCertRows", storageService.countCertificates());
            data.put("readableSummaryLines", outcome.getSummaryLines());
            data.put("readableSummaryText", String.join("\n", outcome.getSummaryLines()));
            data.put("downloadStats", outcome.getStats());
            String message = "未重新解析：使用数据库中已有证书。可调用 POST /cert/detect-conflicts 做冲突检测。";
            return ResponseEntity.ok(Result.ok(data, message));
        }
        Map<String, List<Path>> downloaded = outcome.getFilesByRir();
        List<RpkiCert> toSave = new ArrayList<>();
        Map<String, Object> perRirStats = new LinkedHashMap<>();
        int parseFailed = 0;
        int certCandidates = 0;
        for (Map.Entry<String, List<Path>> entry : downloaded.entrySet()) {
            String rir = entry.getKey();
            int scanned = entry.getValue().size();
            int rirCerCandidates = 0;
            int rirParsed = 0;
            List<String> parseErrors = new ArrayList<>();
            for (Path file : entry.getValue()) {
                String name = file.getFileName().toString().toLowerCase();
                if (!name.endsWith(".cer")) {
                    continue;
                }
                certCandidates++;
                rirCerCandidates++;
                if (certCandidates % 400 == 0) {
                    log.info("证书解析进度：已处理 .cer 约 {} 个（RIR={}）", certCandidates, rir);
                }
                try {
                    RpkCertificateDTO dto = parseService.parseCertificateToDto(file.toString(), rir);
                    RpkiCert entity = parseService.toEntity(dto, FileUtils.readFileToBytes(file.toString()), rir);
                    toSave.add(entity);
                    rirParsed++;
                } catch (Exception e) {
                    parseFailed++;
                    parseErrors.add(file.getFileName().toString() + ": " + e.getMessage());
                    log.warn("解析证书失败 rir={}, file={}", rir, file, e);
                }
            }
            Map<String, Object> oneRir = new LinkedHashMap<>();
            oneRir.put("scannedFiles", scanned);
            oneRir.put("certCandidates", rirCerCandidates);
            oneRir.put("parsedSuccess", rirParsed);
            oneRir.put("parseFailed", parseErrors.size());
            oneRir.put("parseErrors", parseErrors);
            perRirStats.put(rir, oneRir);
        }
        Map<String, RpkiCert> uniqueByHash = new LinkedHashMap<>();
        List<RpkiCert> withoutHash = new ArrayList<>();
        for (RpkiCert c : toSave) {
            if (c.getCertHash() == null) {
                withoutHash.add(c);
            } else {
                uniqueByHash.putIfAbsent(c.getCertHash(), c);
            }
        }
        List<RpkiCert> toPersist = new ArrayList<>(uniqueByHash.values());
        toPersist.addAll(withoutHash);
        log.info("解析阶段结束：去重后待入库 {} 条，开始写库", toPersist.size());
        storageService.saveOrUpdateCertificatesBatch(toPersist);
        for (String rir : downloaded.keySet()) {
            certificateChainService.linkParentsForRir(rir);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("savedCount", toPersist.size());
        data.put("parsedBeforeDedup", toSave.size());
        data.put("rirs", downloaded.keySet());
        data.put("certCandidates", certCandidates);
        data.put("parseFailed", parseFailed);
        data.put("perRirStats", perRirStats);
        data.put("runMode", outcome.getMode());
        data.put("downloadStats", outcome.getStats());
        data.put("readableSummaryLines", outcome.getSummaryLines());
        data.put("readableSummaryText", String.join("\n", outcome.getSummaryLines()));
        Map<String, String> fieldHelp = new LinkedHashMap<>();
        fieldHelp.put("savedCount", "写入数据库的证书条数（同一内容按哈希合并，可能少于路径数）");
        fieldHelp.put("certCandidates", "找到的 .cer 文件个数");
        fieldHelp.put("scannedFiles", "解压并删除非 .cer 后，剩下的待处理路径数（整月 DEMO 下为各日 .cer 之和）");
        fieldHelp.put("runMode", "local-cache*=仅用本地 rpki-data/extracted；demo-month=整月；demo-single=单日 DEMO；multi-rir=多 RIR");
        fieldHelp.put("skippedParse", "true 表示未扫盘入库，直接使用库中已有证书行");
        fieldHelp.put("readableSummaryText", "人类可读摘要，建议优先阅读这一段");
        fieldHelp.put("parsedBeforeDedup", "解析过的 .cer 个数；整月每日同一信任锚时通常大于 savedCount（后者按内容哈希去重后入库）");
        data.put("fieldHelpZh", fieldHelp);
        Map<String, List<String>> cerPathsByRir = new LinkedHashMap<>();
        for (Map.Entry<String, List<Path>> e : downloaded.entrySet()) {
            cerPathsByRir.put(e.getKey(), e.getValue().stream().map(Path::toString).collect(Collectors.toList()));
        }
        data.put("cerPathsByRir", cerPathsByRir);

        String message = "下载解析完成。\n" + String.join("\n", outcome.getSummaryLines());
        return ResponseEntity.ok(Result.ok(data, message));
    }

    @Operation(summary = "触发冲突检测")
    @PostMapping("/detect-conflicts")
    public ResponseEntity<Result<List<ConflictResult>>> detectConflicts(@RequestBody(required = false) DetectConflictRequest request) {
        String rir = request == null ? null : request.getRir();
        List<ConflictResult> results = conflictDetectionService.detectAndPersistConflicts(rir);
        return ResponseEntity.ok(Result.ok(results, "冲突检测完成"));
    }

    @Operation(summary = "分页查询冲突记录")
    @GetMapping("/conflicts")
    public ResponseEntity<Result<Page<ConflictRecord>>> getConflictCerts(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size) {
        return ResponseEntity.ok(Result.ok(storageService.pageConflictRecords(page, size)));
    }

    @Operation(summary = "分页查询证书列表")
    @GetMapping("/certs")
    public ResponseEntity<Result<Page<RpkiCert>>> listCerts(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) String rir,
            @RequestParam(required = false) Boolean hasConflict,
            @RequestParam(required = false) String keyword
    ) {
        return ResponseEntity.ok(Result.ok(storageService.pageCertificates(page, size, rir, hasConflict, keyword)));
    }

    /**
     * 对指定两证做链下内存检测（与全库 {@link #detectConflicts} 分离，避免整库扫描）。
     */
    @Operation(summary = "双证链下冲突检测（内存、不落库）")
    @PostMapping("/detect-pair")
    public ResponseEntity<Result<List<ConflictResult>>> detectPair(@RequestBody DetectPairRequest request) {
        if (request == null || request.getCertIdA() == null || request.getCertIdB() == null) {
            throw new IllegalArgumentException("certIdA 与 certIdB 均不能为空");
        }
        if (request.getCertIdA().equals(request.getCertIdB())) {
            throw new IllegalArgumentException("两个证书 ID 不能相同");
        }
        RpkiCert a = storageService.getCertDetail(request.getCertIdA());
        RpkiCert b = storageService.getCertDetail(request.getCertIdB());
        if (a == null || b == null) {
            throw new IllegalArgumentException("证书不存在：请检查 ID 是否正确");
        }
        List<ConflictResult> raw = conflictDetectionService.detect(List.of(a, b));
        long id1 = Math.min(request.getCertIdA(), request.getCertIdB());
        long id2 = Math.max(request.getCertIdA(), request.getCertIdB());
        List<ConflictResult> filtered = raw.stream()
                .filter(r -> {
                    long ra = r.getCertIdA() == null ? -1 : r.getCertIdA();
                    long rb = r.getCertIdB() == null ? -1 : r.getCertIdB();
                    long mn = Math.min(ra, rb);
                    long mx = Math.max(ra, rb);
                    return mn == id1 && mx == id2;
                })
                .collect(Collectors.toList());
        return ResponseEntity.ok(Result.ok(filtered, "双证链下检测完成"));
    }

    /**
     * 对指定两证做链下检测并写库：新增 conflict_record，并回写 rpki_cert.has_conflict/conflict_details。
     */
    @Operation(summary = "双证链下冲突检测（持久化到数据库）")
    @PostMapping("/detect-pair-persist")
    public ResponseEntity<Result<List<ConflictResult>>> detectPairPersist(@RequestBody DetectPairRequest request) {
        if (request == null || request.getCertIdA() == null || request.getCertIdB() == null) {
            throw new IllegalArgumentException("certIdA 与 certIdB 均不能为空");
        }
        List<ConflictResult> results = conflictDetectionService.detectPairAndPersist(request.getCertIdA(), request.getCertIdB());
        return ResponseEntity.ok(Result.ok(results, "双证链下检测完成（已写入冲突记录）"));
    }

    @Operation(summary = "分页查询双证检测流水（含未检出冲突）")
    @GetMapping("/pair-detection-records")
    public ResponseEntity<Result<Page<PairDetectionRecord>>> listPairDetectionRecords(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "15") long size
    ) {
        return ResponseEntity.ok(Result.ok(storageService.pagePairDetectionRecords(page, size)));
    }

    @Operation(summary = "记录一次双证检测流水（前端「开始检测」完成后上报）")
    @PostMapping("/pair-detection-records")
    public ResponseEntity<Result<Long>> recordPairDetection(@RequestBody RecordPairDetectionRequest request) {
        if (request == null || request.getCertIdA() == null || request.getCertIdB() == null) {
            throw new IllegalArgumentException("certIdA 与 certIdB 均不能为空");
        }
        if (request.getCertIdA().equals(request.getCertIdB())) {
            throw new IllegalArgumentException("两个证书 ID 不能相同");
        }
        if (request.getOfflineHasConflict() == null) {
            throw new IllegalArgumentException("offlineHasConflict 不能为空");
        }
        String offlineSummary = request.getOfflineSummary();
        if (offlineSummary == null || offlineSummary.isBlank()) {
            offlineSummary = Boolean.TRUE.equals(request.getOfflineHasConflict()) ? "有冲突（未提供摘要）" : "无冲突";
        }
        PairDetectionRecord rec = PairDetectionRecord.builder()
                .certIdA(request.getCertIdA())
                .certIdB(request.getCertIdB())
                .offlineHasConflict(request.getOfflineHasConflict())
                .offlineSummary(offlineSummary.trim())
                .chainVerdict(blankToNull(request.getChainVerdict()))
                .chainPrimaryType(blankToNull(request.getChainPrimaryType()))
                .chainSummary(request.getChainSummary() != null && !request.getChainSummary().isBlank()
                        ? request.getChainSummary().trim() : null)
                .build();
        storageService.savePairDetectionRecord(rec);
        return ResponseEntity.ok(Result.ok(rec.getId(), "检测记录已保存"));
    }

    private static String blankToNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.trim();
    }

    @Operation(summary = "证书详情和冲突结果")
    @GetMapping("/{id}/detail")
    public ResponseEntity<Result<Map<String, Object>>> detail(@PathVariable Long id) {
        if (id == null || id <= 0) {
            throw new IllegalArgumentException("证书ID不合法");
        }
        RpkiCert cert = storageService.getCertDetail(id);
        List<ConflictRecord> conflicts = cert == null ? List.of() : storageService.listConflictsByCertId(id);
        Map<String, Object> data = new HashMap<>();
        data.put("certificate", cert);
        data.put("conflicts", conflicts);
        String msg = cert == null ? "证书不存在，返回空结果" : "OK";
        return ResponseEntity.ok(Result.ok(data, msg));
    }

    @Operation(summary = "递归扫描本地目录下全部 .cer 并批量入库（适合 afrinic.tal/.../out 等多层哈希结构）")
    @PostMapping("/bulk-import-directory")
    public ResponseEntity<Result<BulkDirectoryImportResult>> bulkImportDirectory(
            @RequestParam String rootPath,
            @RequestParam(required = false) String rir,
            @RequestParam(defaultValue = "400") int batchSize
    ) {
        BulkDirectoryImportResult result = rpkiBulkDirectoryImportService.importDirectory(
                Paths.get(rootPath), rir, batchSize);
        return ResponseEntity.ok(Result.ok(result, "批量导入完成"));
    }

    @Operation(summary = "将冲突结果手动推送到Fabric")
    @PostMapping("/push-to-fabric")
    public ResponseEntity<Result<Map<String, Object>>> pushToFabric(@RequestParam Long conflictId) {
        ConflictRecord record = storageService.getConflictById(conflictId);
        if (record == null) {
            throw new IllegalArgumentException("冲突ID不存在");
        }
        ConflictResult payload = ConflictResult.builder()
                .certIdA(record.getCertIdA())
                .certIdB(record.getCertIdB())
                .conflictType(record.getConflictType())
                .severity(record.getSeverity())
                .ruleVersion(record.getRuleVersion())
                .ruleReference(record.getRuleReference())
                .details(record.getDetails())
                .build();
        String txId = fabricService.submitConflictToChain(payload);
        return ResponseEntity.ok(Result.ok(Map.of("txId", txId), "推送成功"));
    }
}
