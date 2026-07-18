package com.rpki.conflictchecker.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rpki.conflictchecker.dto.ConflictResult;
import com.rpki.conflictchecker.dto.RpkCertificateDTO;
import com.rpki.conflictchecker.dto.test.FunctionalTestCaseResultDto;
import com.rpki.conflictchecker.dto.test.FunctionalTestRunReportDto;
import com.rpki.conflictchecker.entity.RpkiCert;
import com.rpki.conflictchecker.service.fabric.FabricBlockchainFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 读取 {@code qidongjiaoben/test-data/functional-manifest.json} 与样例 .cer，
 * 在内存中构建 {@link RpkiCert}（仅用于检测，不写库），调用 {@link RpkiUnifiedConflictDetectionService#detectInMemory(java.util.List)}。
 * 链上项调用 {@link FabricBlockchainFacade#detectConflictOnChain}，在 MOCK 模式下仅记录摘要，不判与链下是否一致。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FunctionalTestService {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    private final CertificateParseService certificateParseService;
    private final RpkiUnifiedConflictDetectionService unifiedConflictDetectionService;
    private final FabricBlockchainFacade fabricBlockchainFacade;

    @Value("${rpki.test.functional.data-dir:qidongjiaoben/test-data}")
    private String dataDir;

    private final AtomicReference<FunctionalTestRunReportDto> lastReport = new AtomicReference<>();

    public FunctionalTestRunReportDto getLastReport() {
        return lastReport.get();
    }

    /**
     * 执行全部用例并缓存最近一次报告（供 GET /test/functional/report）。
     */
    public FunctionalTestRunReportDto runAll() throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).resolve(dataDir).normalize();
        Path manifestPath = root.resolve("functional-manifest.json");
        if (!Files.isRegularFile(manifestPath)) {
            throw new IllegalStateException("未找到 manifest: " + manifestPath
                    + "。请先执行: mvn -q -Dtest=TestDataGenerator#generateAll test");
        }
        String json = Files.readString(manifestPath, StandardCharsets.UTF_8);
        JsonObject manifest = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        JsonArray cases = manifest.getAsJsonArray("cases");
        long t0 = System.currentTimeMillis();
        List<FunctionalTestCaseResultDto> out = new ArrayList<>();
        int pass = 0, fail = 0;
        for (JsonElement el : cases) {
            JsonObject c = el.getAsJsonObject();
            FunctionalTestCaseResultDto one = runOneCase(root, c);
            out.add(one);
            if (one.isOfflinePass()) {
                pass++;
            } else {
                fail++;
            }
        }
        long dt = System.currentTimeMillis() - t0;
        String summary = String.format(
                Locale.ROOT,
                "共执行 %d 个功能测试用例；链下通过 %d 条，未通过 %d 条。Fabric.mode=%s 时"
                        + " 链上为烟雾调用，不用于与链下比对一致性。",
                out.size(), pass, fail,
                fabricBlockchainFacade.isReal() ? "REAL" : "MOCK");
        FunctionalTestRunReportDto report = FunctionalTestRunReportDto.builder()
                .runAt(Instant.now())
                .dataDir(root.toString())
                .totalDurationMs(dt)
                .caseCount(out.size())
                .passedOffline(pass)
                .failedOffline(fail)
                .fabricMode(fabricBlockchainFacade.isReal() ? "REAL" : "MOCK")
                .cases(out)
                .summaryTemplate(summary)
                .build();
        lastReport.set(report);
        writeLastReportToMarkdown();
        return report;
    }

    private FunctionalTestCaseResultDto runOneCase(Path root, JsonObject c) throws Exception {
        long t0 = System.currentTimeMillis();
        String id = c.get("id").getAsString();
        String name = c.get("name").getAsString();
        String defRef = c.has("defRef") ? c.get("defRef").getAsString() : "";
        String formal = c.has("formalDefNote") && !c.get("formalDefNote").isJsonNull()
                ? c.get("formalDefNote").getAsString() : null;
        String kind = c.has("kind") ? c.get("kind").getAsString() : "PEER";
        List<String> expected = toStringList(c.get("expectedOfflineTypes").getAsJsonArray());

        JsonArray entries = c.getAsJsonArray("entries");
        List<RpkiCert> certs = new ArrayList<>();
        List<String> fileNames = new ArrayList<>();
        for (JsonElement e : entries) {
            JsonObject en = e.getAsJsonObject();
            String f = en.get("file").getAsString();
            fileNames.add(f);
            long certId = en.get("id").getAsLong();
            Long parent = !en.has("parentId") || en.get("parentId") == null || en.get("parentId").isJsonNull()
                    ? null
                    : en.get("parentId").getAsLong();
            boolean revoked = en.has("revoked") && en.get("revoked").getAsBoolean();
            Path p = root.resolve("certs").resolve(f);
            byte[] der = Files.readAllBytes(p);
            RpkCertificateDTO dto = certificateParseService.parseCertificateToDtoFromBytes(der, f, "TEST");
            RpkiCert entity = certificateParseService.toEntity(dto, der, "TEST");
            entity.setId(certId);
            entity.setParentCertId(parent);
            entity.setRevoked(revoked);
            // 合成证书法书有效期若未覆盖「当前 UTC」会被 filterValidAt 整批剔除，导致成对空跑
            LocalDateTime w0 = LocalDateTime.now(ZoneOffset.UTC).minusDays(1);
            LocalDateTime w1 = w0.plusYears(20);
            entity.setNotBefore(w0);
            entity.setNotAfter(w1);
            certs.add(entity);
        }
        certs.sort(Comparator.comparing(RpkiCert::getId));

        List<ConflictResult> raw = unifiedConflictDetectionService.detectInMemory(certs);
        Set<String> types = new LinkedHashSet<>();
        for (ConflictResult r : raw) {
            if (r.getConflictType() != null) {
                types.add(r.getConflictType());
            }
        }
        List<String> actualList = new ArrayList<>(types);
        boolean offPass = true;
        for (String ex : expected) {
            if (!types.contains(ex)) {
                offPass = false;
                break;
            }
        }
        if (!offPass) {
            log.warn("[functional] {} 链下未命中全部预期: expected={} actual={}", id, expected, actualList);
        }

        // 链上：对 primary 两证取 hash（若未定义则取 id 最小两证）
        long idA;
        long idB;
        if (c.has("primaryPair") && !c.get("primaryPair").isJsonNull() && c.get("primaryPair").isJsonArray()) {
            JsonArray pp = c.getAsJsonArray("primaryPair");
            idA = pp.get(0).getAsLong();
            idB = pp.get(1).getAsLong();
        } else {
            List<Long> ids = certs.stream().map(RpkiCert::getId).sorted().toList();
            if (ids.size() >= 2) {
                idA = ids.get(0);
                idB = ids.get(1);
            } else {
                idA = -1;
                idB = -1;
            }
        }
        RpkiCert ca = findById(certs, idA);
        RpkiCert cb = findById(certs, idB);
        String h1 = ca != null ? ca.getCertHash() : "";
        String h2 = cb != null ? cb.getCertHash() : "";
        String onJson = (ca != null && cb != null) ? fabricBlockchainFacade.detectConflictOnChain(h1, h2) : "{}";
        String verdict = readJsonString(onJson, "verdict");
        String primary = readJsonString(onJson, "primaryConflictType");
        boolean isMock = !fabricBlockchainFacade.isReal();
        String note = isMock
                ? "MOCK: 链上 verdict/primary 为固定占位，不用于与链下 expected 比较"
                : "REAL: 可对照链下 primary 与链上 primaryConflictType（规则口径可能不同，仅作存证层参考）";
        boolean chainMatch = !isMock && !expected.isEmpty() && primary != null
                && expected.stream().anyMatch(p -> p != null && p.equalsIgnoreCase(primary));

        long ms = System.currentTimeMillis() - t0;
        return FunctionalTestCaseResultDto.builder()
                .caseId(id)
                .name(name)
                .defRef(defRef)
                .formalDefNote(formal)
                .kind(kind)
                .certFiles(fileNames)
                .expectedOfflineTypes(expected)
                .actualOfflineTypes(actualList)
                .offlinePass(offPass)
                .onChainSummary(trimJson(onJson))
                .onChainVerdict(verdict)
                .onChainPrimaryType(primary)
                .chainConsistentWithOffline(chainMatch)
                .onChainNote(note)
                .durationMs(ms)
                .build();
    }

    private static RpkiCert findById(List<RpkiCert> certs, long id) {
        for (RpkiCert c : certs) {
            if (c.getId() != null && c.getId() == id) {
                return c;
            }
        }
        return null;
    }

    private static String readJsonString(String json, String key) {
        try {
            JsonObject o = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static String trimJson(String json) {
        if (json == null) {
            return "";
        }
        if (json.length() > 4000) {
            return json.substring(0, 4000) + "...(trimmed)";
        }
        return json;
    }

    private static List<String> toStringList(JsonArray a) {
        List<String> l = new ArrayList<>();
        for (JsonElement e : a) {
            l.add(e.getAsString());
        }
        return l;
    }

    /**
     * 将最近一次报告写入 qidongjiaoben/测试结果-功能测试.md（若目录存在且已跑过 runAll）。
     */
    public void writeLastReportToMarkdown() {
        FunctionalTestRunReportDto r = lastReport.get();
        if (r == null) {
            return;
        }
        try {
            Path md = Path.of(System.getProperty("user.dir")).resolve("qidongjiaoben").resolve("测试结果-功能测试.md");
            StringBuilder sb = new StringBuilder();
            sb.append("<!-- 本文件由 FunctionalTestService 在 runAll 后辅助生成，可手工修改表内数字。 -->\n");
            sb.append("## 功能测试结果 (Table 4-1)\n\n");
            sb.append("| 用例ID | 场景 | 证书文件 | 链下预期类型 | 链下实际 | 通过 | 链上 verdict | 备注 |\n");
            sb.append("|--------|------|----------|-------------|----------|------|-------------|------|\n");
            for (FunctionalTestCaseResultDto c : r.getCases()) {
                sb.append("| ").append(c.getCaseId()).append(" | ")
                        .append(escapeCell(c.getName())).append(" | ")
                        .append(escapeCell(String.join(", ", c.getCertFiles()))).append(" | ")
                        .append(escapeCell(String.join(",", c.getExpectedOfflineTypes()))).append(" | ")
                        .append(escapeCell(String.join(",", c.getActualOfflineTypes()))).append(" | ")
                        .append(c.isOfflinePass() ? "是" : "否").append(" | ")
                        .append(escapeCell(Objects.toString(c.getOnChainVerdict(), ""))).append(" | ")
                        .append(escapeCell(Objects.toString(c.getOnChainNote(), ""))).append(" |\n");
            }
            sb.append("\n> ").append(r.getSummaryTemplate()).append("\n");
            Files.writeString(md, sb.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.debug("写 测试结果-功能测试.md 跳过: {}", e.getMessage());
        }
    }

    private static String escapeCell(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\n", " ").replace("|", "\\|");
    }
}
