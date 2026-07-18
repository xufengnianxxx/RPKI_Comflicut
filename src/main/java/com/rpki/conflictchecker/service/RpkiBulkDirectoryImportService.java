package com.rpki.conflictchecker.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.rpki.conflictchecker.config.AsyncBulkImportConfig;
import com.rpki.conflictchecker.dto.BulkDirectoryImportResult;
import com.rpki.conflictchecker.entity.RpkiCert;
import com.rpki.conflictchecker.util.CertificateKeyIds;
import com.rpki.conflictchecker.util.CertificateUtils;
import com.rpki.conflictchecker.util.FileUtils;
import com.rpki.conflictchecker.util.Rfc3779ResourceParser;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.Extensions;
import org.bouncycastle.cert.X509CertificateHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * 针对 AFRINIC 等多层哈希目录（如 {@code afrinic.tal/.../unvalidated/.../member_repository/...}）
 * 递归扫描 {@code .cer}，用 BouncyCastle 解析 X.509 与 RFC 3779 扩展，并写入 {@link RpkiCert} 表。
 *
 * <p>说明：BouncyCastle 1.76 将 RFC 3779 的 ASN.1 结构以 {@link org.bouncycastle.asn1.ASN1Sequence} /
 * {@link org.bouncycastle.asn1.ASN1BitString} 等形式暴露；本服务通过 {@link Rfc3779ResourceParser}
 * 遍历与 {@code IPAddressOrRange} / {@code ASIdOrRange} 等价的编码，行为与 PKIX 高层类型一致。</p>
 *
 * <p>资源字段 JSON 使用 Gson 序列化 {@link List}{@code <String>}，与 {@link CertificateParseService}、
 * {@link ConflictDetectionService#parseJsonList} 完全兼容。</p>
 */
@Slf4j
@Service
public class RpkiBulkDirectoryImportService {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /** 单文件上限，防止异常大文件拖垮堆内存 */
    private static final long MAX_CER_BYTES = 20L * 1024 * 1024;

    private static final int DEFAULT_BATCH_SIZE = 400;
    private static final int MAX_FAILURE_DETAILS = 300;

    @Autowired
    private StorageService storageService;

    @Autowired
    private CertificateChainService certificateChainService;

    /**
     * 同步导入：适合 CLI / 小目录；大仓库请用 {@link #importDirectoryAsync(Path, String, int)}。
     *
     * @param root        根目录（须存在且可读）
     * @param rirOverride 非空时强制作为 {@code rir}；否则从路径推断（如 {@code *.tal} 段、目录名含 afrinic）
     * @param batchSize   每批写入条数（去重后）
     */
    public BulkDirectoryImportResult importDirectory(Path root, String rirOverride, int batchSize) {
        long t0 = System.currentTimeMillis();
        Path absRoot = root.toAbsolutePath().normalize();
        BulkDirectoryImportResult.BulkDirectoryImportResultBuilder rb = BulkDirectoryImportResult.builder()
                .rootPath(absRoot.toString())
                .rirsLinked(new ArrayList<>());

        if (!Files.isDirectory(absRoot)) {
            return rb.durationMs(System.currentTimeMillis() - t0)
                    .filesDiscovered(0)
                    .rowsSavedAfterDedup(0)
                    .failures(List.of(failure(absRoot.toString(), "不是目录或不存在")))
                    .build();
        }

        int bs = batchSize > 0 ? batchSize : DEFAULT_BATCH_SIZE;
        long discovered = 0;
        long ok = 0;
        long fail = 0;
        long rowsSaved = 0;
        List<BulkDirectoryImportResult.FailureDetail> failures = new ArrayList<>();
        Set<String> rirsTouched = new LinkedHashSet<>();

        List<RpkiCert> batch = new ArrayList<>(bs);

        try (Stream<Path> walk = Files.walk(absRoot, FileVisitOption.FOLLOW_LINKS)) {
            List<Path> cerFiles = walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".cer"))
                    .sorted()
                    .toList();

            discovered = cerFiles.size();
            log.info("bulk-import: root={} discovered {} .cer files, batchSize={}", absRoot, discovered, bs);

            for (Path cer : cerFiles) {
                try {
                    long sz = Files.size(cer);
                    if (sz > MAX_CER_BYTES) {
                        throw new IOException("文件过大: " + sz + " bytes (max " + MAX_CER_BYTES + ")");
                    }
                    RpkiCert entity = parseCerFile(cer, absRoot, rirOverride);
                    batch.add(entity);
                    ok++;
                    if (entity.getRir() != null) {
                        rirsTouched.add(entity.getRir());
                    }
                    if (batch.size() >= bs) {
                        rowsSaved += flushBatch(batch, failures);
                        batch.clear();
                    }
                } catch (Exception e) {
                    fail++;
                    log.warn("bulk-import: 解析失败 path={} reason={}", cer, e.toString());
                    if (failures.size() < MAX_FAILURE_DETAILS) {
                        failures.add(failure(cer.toString(), e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
                    }
                }
            }
            if (!batch.isEmpty()) {
                rowsSaved += flushBatch(batch, failures);
                batch.clear();
            }
        } catch (IOException e) {
            log.error("bulk-import: 遍历目录失败 {}", absRoot, e);
            failures.add(failure(absRoot.toString(), "遍历失败: " + e.getMessage()));
        }

        for (String rir : rirsTouched) {
            try {
                certificateChainService.linkParentsForRir(rir);
            } catch (Exception e) {
                log.warn("bulk-import: 父子链回填失败 rir={} {}", rir, e.toString());
            }
        }

        return rb.filesDiscovered(discovered)
                .parseSuccess(ok)
                .parseFailed(fail)
                .rowsSavedAfterDedup(rowsSaved)
                .failures(failures)
                .rirsLinked(new ArrayList<>(rirsTouched))
                .durationMs(System.currentTimeMillis() - t0)
                .build();
    }

    @Async(AsyncBulkImportConfig.BULK_IMPORT_EXECUTOR)
    public CompletableFuture<BulkDirectoryImportResult> importDirectoryAsync(Path root, String rirOverride, int batchSize) {
        return CompletableFuture.completedFuture(importDirectory(root, rirOverride, batchSize));
    }

    /** @return 本批去重后尝试写入的行数（与 UPSERT 后库中实际行数可能略有差异） */
    private int flushBatch(List<RpkiCert> batch, List<BulkDirectoryImportResult.FailureDetail> failures) {
        if (batch.isEmpty()) {
            return 0;
        }
        Map<String, RpkiCert> byHash = new LinkedHashMap<>();
        List<RpkiCert> noHash = new ArrayList<>();
        for (RpkiCert c : batch) {
            if (c.getCertHash() == null || c.getCertHash().isBlank()) {
                noHash.add(c);
            } else {
                byHash.putIfAbsent(c.getCertHash(), c);
            }
        }
        List<RpkiCert> toSave = new ArrayList<>(byHash.values());
        toSave.addAll(noHash);
        try {
            storageService.saveOrUpdateCertificatesBatch(toSave);
            return toSave.size();
        } catch (Exception e) {
            log.error("bulk-import: 批量写库失败 size={} {}", toSave.size(), e.toString());
            if (failures.size() < MAX_FAILURE_DETAILS) {
                failures.add(failure("(batch)", "写库失败: " + e.getMessage()));
            }
            return 0;
        }
    }

    /**
     * 解析单个 .cer：DN / 序列号(十六进制大写) / UTC 时间 / RFC3779 / SHA-256 / Base64 原文。
     */
    public RpkiCert parseCerFile(Path cerPath, Path rootDir, String rirOverride) throws Exception {
        byte[] raw = Files.readAllBytes(cerPath);
        String certHash = FileUtils.calculateSHA256FromBytes(raw);
        X509Certificate x509 = CertificateUtils.parseCertificate(raw);
        X509CertificateHolder holder = CertificateUtils.parseCertificateHolder(raw);

        String rir = resolveRir(cerPath, rootDir, rirOverride);
        String serialHex = x509.getSerialNumber().toString(16).toUpperCase(Locale.ROOT);
        String serialShort = serialHex.length() > 8 ? serialHex.substring(0, 8) : serialHex;
        String pathToken = pathFingerprint(cerPath, rootDir);
        String certName = buildCertName(rir, serialShort, pathToken);

        List<String> ipv4 = new ArrayList<>();
        List<String> ipv6 = new ArrayList<>();
        List<String> asn = new ArrayList<>();
        Extensions extensions = holder.getExtensions();
        fillRfc3779(extensions, ipv4, ipv6, asn);

        String ski = CertificateKeyIds.subjectKeyIdHex(extensions);
        String aki = CertificateKeyIds.authorityKeyIdHex(extensions);

        String cerFileName = cerPath.getFileName() != null ? cerPath.getFileName().toString() : null;

        LocalDateTime nb = LocalDateTime.ofInstant(x509.getNotBefore().toInstant(), ZoneOffset.UTC);
        LocalDateTime na = LocalDateTime.ofInstant(x509.getNotAfter().toInstant(), ZoneOffset.UTC);

        RpkiCert cert = new RpkiCert();
        cert.setCertName(certName);
        cert.setCerFileName(cerFileName);
        cert.setSerialNumber(serialHex);
        cert.setIssuer(x509.getIssuerX500Principal().getName());
        cert.setSubject(x509.getSubjectX500Principal().getName());
        cert.setRir(rir);
        cert.setNotBefore(nb);
        cert.setNotAfter(na);
        cert.setIpv4Prefixes(GSON.toJson(ipv4));
        cert.setIpv6Prefixes(GSON.toJson(ipv6));
        cert.setAsNumbers(GSON.toJson(asn));
        cert.setSubjectKeyId(ski);
        cert.setAuthorityKeyId(aki);
        cert.setParentCertId(null);
        cert.setRevoked(false);
        cert.setHasConflict(false);
        cert.setConflictDetails(null);
        cert.setFabricTxId(null);
        cert.setFabricBlockNum(null);
        cert.setIsSentToFabric(false);
        cert.setFabricSendTime(null);
        cert.setCertHash(certHash);
        cert.setRawCertData(Base64.getEncoder().encodeToString(raw));
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        cert.setCreatedAt(now);
        cert.setUpdatedAt(now);
        return cert;
    }

    private static void fillRfc3779(Extensions extensions, List<String> ipv4, List<String> ipv6, List<String> asn) {
        if (extensions == null) {
            return;
        }
        try {
            Extension ipExt = extensions.getExtension(Rfc3779ResourceParser.OID_IP_ADDR_BLOCKS);
            if (ipExt != null) {
                ASN1Encodable parsed = ipExt.getParsedValue();
                if (parsed == null) {
                    parsed = ASN1Primitive.fromByteArray(ipExt.getExtnValue().getOctets());
                }
                Rfc3779ResourceParser.parseIpResources(parsed, ipv4, ipv6);
            }
        } catch (Exception e) {
            log.debug("RFC3779 ipAddrBlocks 解析跳过: {}", e.getMessage());
        }
        try {
            Extension asExt = extensions.getExtension(Rfc3779ResourceParser.OID_AS_IDS);
            if (asExt != null) {
                ASN1Encodable parsed = asExt.getParsedValue();
                if (parsed == null) {
                    parsed = ASN1Primitive.fromByteArray(asExt.getExtnValue().getOctets());
                }
                asn.addAll(Rfc3779ResourceParser.parseAsResources(parsed));
            }
        } catch (Exception e) {
            log.debug("RFC3779 autonomousSysNum 解析跳过: {}", e.getMessage());
        }
    }

    private static String resolveRir(Path file, Path root, String override) {
        if (override != null && !override.isBlank()) {
            return override.trim().toUpperCase(Locale.ROOT);
        }
        Path rel = root.relativize(file);
        for (Path p : rel) {
            String name = p.getFileName().toString();
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".tal")) {
                return name.substring(0, name.length() - 4).toUpperCase(Locale.ROOT).replace('.', '_');
            }
        }
        String full = file.toString().toLowerCase(Locale.ROOT);
        if (full.contains("afrinic")) {
            return "AFRINIC";
        }
        if (full.contains("apnic")) {
            return "APNIC";
        }
        if (full.contains("ripe")) {
            return "RIPE";
        }
        if (full.contains("arin")) {
            return "ARIN";
        }
        if (full.contains("lacnic")) {
            return "LACNIC";
        }
        return "UNKNOWN";
    }

    /**
     * 取相对路径上最后若干段（通常为哈希目录名），拼成 cert_name 后缀，避免仅依赖序列号撞名。
     */
    private static String pathFingerprint(Path file, Path root) {
        Path rel = root.relativize(file.getParent() == null ? file : file.getParent());
        List<String> parts = new ArrayList<>();
        for (Path p : rel) {
            parts.add(p.getFileName().toString());
        }
        int n = parts.size();
        int from = Math.max(0, n - 3);
        String joined = String.join("_", parts.subList(from, n));
        if (joined.length() > 80) {
            joined = joined.substring(joined.length() - 80);
        }
        if (joined.isEmpty()) {
            joined = "root";
        }
        return joined.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static String buildCertName(String rir, String serialShort, String pathToken) {
        String base = rir + "_" + serialShort + "_" + pathToken;
        if (base.length() > 255) {
            return base.substring(0, 255);
        }
        return base;
    }

    private static BulkDirectoryImportResult.FailureDetail failure(String path, String reason) {
        return BulkDirectoryImportResult.FailureDetail.builder().path(path).reason(reason).build();
    }
}
