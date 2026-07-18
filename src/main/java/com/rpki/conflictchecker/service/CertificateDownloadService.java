package com.rpki.conflictchecker.service;

import com.rpki.conflictchecker.dto.DownloadExtractOutcome;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpHead;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Service
public class CertificateDownloadService {

    @Value("${rpki.download.dir:/tmp/rpki/downloads}")
    private String downloadDir;

    @Value("${rpki.extract.dir:/tmp/rpki/extracted}")
    private String extractDir;

    /** 为 false 时不执行 {@link #scheduledSync} 内的拉取（默认关闭，避免每次部署重复下载） */
    @Value("${rpki.sync.scheduled-enabled:false}")
    private boolean scheduledEnabled;

    /**
     * 解压目录下已有 .cer 时，跳过 HTTP 下载，直接扫描本地（配合项目内 rpki-data 目录做「一次下载、多次启动」）。
     */
    @Value("${rpki.sync.skip-download-if-local:true}")
    private boolean skipDownloadIfLocal;

    @Value("${rpki.sync.retry-count:3}")
    private int retryCount;

    @Value("${rpki.sync.rirs:RIPE,APNIC,ARIN}")
    private String rirList;

    /**
     * 为 true 时，在 {@link #mirrorBase} 下按「年/月/日」回退查找 repo.tar.xz（RIPE 归档已取消固定的 …/latest/ 路径）。
     */
    @Value("${rpki.sources.auto-resolve-latest:true}")
    private boolean autoResolveLatest;

    @Value("${rpki.sources.mirror-base:https://ftp.ripe.net/rpki/}")
    private String mirrorBase;

    /** auto-resolve-latest=false 时使用的固定完整 URL（需自行保证可访问） */
    @Value("${rpki.sources.ripe:https://ftp.ripe.net/rpki/ripencc.tal/2026/04/09/repo.tar.xz}")
    private String ripeUrl;

    @Value("${rpki.sources.apnic:https://ftp.ripe.net/rpki/apnic.tal/2026/04/09/repo.tar.xz}")
    private String apnicUrl;

    @Value("${rpki.sources.arin:https://ftp.ripe.net/rpki/arin.tal/2026/04/09/repo.tar.xz}")
    private String arinUrl;

    /**
     * 演示用固定快照（完整 repo.tar.xz URL）。在 {@code rpki.sync.rirs} 中包含 {@code DEMO} 时生效。
     */
    @Value("${rpki.sources.demo:}")
    private String demoRepoUrl;

    /**
     * 若配置且 {@code rpki.sync.rirs} 含 DEMO，则按「月」拉取每日 repo.tar.xz（URL = base + /DD/repo.tar.xz）。
     * 参考目录索引：<a href="https://ftp.ripe.net/rpki/apnic-afrinic.tal/2018/01/">…/2018/01/</a>
     */
    @Value("${rpki.sources.demo-month-base:}")
    private String demoMonthBase;

    /** 与 demo-month-base 搭配，默认 31（1 月）；2 月可改为 28/29 */
    @Value("${rpki.sources.demo-month-max-day:31}")
    private int demoMonthMaxDay;

    public DownloadExtractOutcome downloadAndExtractAllRirs() {
        return downloadAndExtractAllRirs(false);
    }

    /**
     * @param forceNetwork true 时始终走网络（定时任务应传 true）；false 时若本地已有 .cer 则只扫盘
     */
    public DownloadExtractOutcome downloadAndExtractAllRirs(boolean forceNetwork) {
        if (!forceNetwork && skipDownloadIfLocal && hasLocalCerUnderExtract()) {
            log.info("使用本地已解压证书，跳过网络下载（{}）", extractDir);
            return buildOutcomeFromLocalExtract();
        }
        if (demoMonthConfigured()) {
            return downloadDemoMonth();
        }
        Map<String, String> sources = buildSources();
        Map<String, List<Path>> result = new LinkedHashMap<>();
        List<String> lines = new ArrayList<>();
        lines.add("【模式】多 RIR 同步（或单日 DEMO）");
        for (Map.Entry<String, String> entry : sources.entrySet()) {
            try {
                List<Path> files = downloadWithRetry(entry.getKey(), entry.getValue());
                result.put(entry.getKey(), files);
                lines.add("【" + entry.getKey() + "】解压后待处理文件数（含已删非 .cer 时仅 .cer）= " + files.size());
            } catch (RuntimeException e) {
                log.warn("RIR={} 同步失败，跳过该源继续执行: {}", entry.getKey(), e.getMessage());
                result.put(entry.getKey(), List.of());
                lines.add("【" + entry.getKey() + "】失败 — " + e.getMessage());
            }
        }
        lines.add("【说明】仅 DEMO 源会在解压后删除 .roa / .mft / .crl；RIPE/APNIC/ARIN 仍保留全部类型文件。");
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("rirCount", result.size());
        stats.put("totalFilesListed", result.values().stream().mapToInt(List::size).sum());
        return DownloadExtractOutcome.builder()
                .filesByRir(result)
                .summaryLines(lines)
                .mode(result.size() == 1 && result.containsKey("DEMO") ? "demo-single" : "multi-rir")
                .stats(stats)
                .build();
    }

    private boolean demoMonthConfigured() {
        if (demoMonthBase == null || demoMonthBase.isBlank()) {
            return false;
        }
        return Arrays.stream(rirList.split(","))
                .map(String::trim)
                .map(String::toUpperCase)
                .anyMatch("DEMO"::equals);
    }

    @Scheduled(cron = "${rpki.sync.cron:0 0 2 * * ?}")
    public void scheduledSync() {
        if (!scheduledEnabled) {
            return;
        }
        log.info("触发定时同步任务，日期={}", LocalDate.now());
        try {
            DownloadExtractOutcome o = downloadAndExtractAllRirs(true);
            log.info("定时同步任务完成 mode={} lines={}", o.getMode(), o.getSummaryLines());
        } catch (Exception e) {
            log.error("定时同步失败", e);
        }
    }

    private boolean hasLocalCerUnderExtract() {
        Path root = Paths.get(extractDir).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            return false;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.anyMatch(p -> Files.isRegularFile(p)
                    && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".cer"));
        } catch (IOException e) {
            log.warn("扫描本地解压目录失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 按解压目录下一级子目录名（demo / ripe / apnic / arin）归类；若无子目录但根下直接有 .cer，则归入 rirs 配置中的第一个 RIR。
     */
    private DownloadExtractOutcome buildOutcomeFromLocalExtract() {
        Path root = Paths.get(extractDir).toAbsolutePath().normalize();
        Map<String, List<Path>> result = new LinkedHashMap<>();
        List<String> lines = new ArrayList<>();
        lines.add("【模式】本地缓存（未访问网络）");
        lines.add("【解压目录】" + root);
        try {
            if (Files.isDirectory(root)) {
                try (Stream<Path> entries = Files.list(root)) {
                    List<Path> subdirs = entries.filter(Files::isDirectory).sorted().toList();
                    for (Path sub : subdirs) {
                        String rirKey = sub.getFileName().toString().toUpperCase(Locale.ROOT);
                        if (!knownRir(rirKey)) {
                            continue;
                        }
                        List<Path> cers = scanCerFiles(sub);
                        if (!cers.isEmpty()) {
                            result.put(rirKey, cers);
                            lines.add("【" + rirKey + "】本地 .cer 数 = " + cers.size());
                        }
                    }
                }
                if (result.isEmpty()) {
                    List<Path> flat = scanCerFiles(root);
                    if (!flat.isEmpty()) {
                        String rirKey = firstRirFromListOrDemo();
                        result.put(rirKey, flat);
                        lines.add("【" + rirKey + "】根目录下 .cer 数 = " + flat.size());
                    }
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("读取本地证书目录失败: " + root, e);
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("fromLocalCache", true);
        stats.put("extractDir", root.toString());
        stats.put("totalFilesListed", result.values().stream().mapToInt(List::size).sum());
        stats.put("rirCount", result.size());
        String mode = result.size() == 1 && result.containsKey("DEMO") ? "local-cache-demo" : "local-cache";
        return DownloadExtractOutcome.builder()
                .filesByRir(result)
                .summaryLines(lines)
                .mode(mode)
                .stats(stats)
                .build();
    }

    private String firstRirFromListOrDemo() {
        return Arrays.stream(rirList.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toUpperCase(Locale.ROOT))
                .filter(this::knownRir)
                .findFirst()
                .orElse("DEMO");
    }

    private DownloadExtractOutcome downloadDemoMonth() {
        String base = demoMonthBase.trim().replaceAll("/+$", "");
        String[] ym = parseYearMonthFromMonthBase(base);
        String year = ym[0];
        String month = ym[1];
        List<String> lines = new ArrayList<>();
        lines.add("【模式】演示：整月下载（每日一个 repo.tar.xz）");
        lines.add("【归档前缀】" + base);
        lines.add("【年月】" + year + " 年 " + month + " 月，最多尝试 " + demoMonthMaxDay + " 天");
        lines.add("【清理】每天解压后删除 .roa / .mft / .crl，只保留 .cer，并尽量删除空目录");

        List<Path> allCers = new ArrayList<>();
        int daysOk = 0;
        int daysMissing = 0;
        int daysFailed = 0;
        List<Map<String, Object>> perDay = new ArrayList<>();

        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            int max = Math.min(Math.max(demoMonthMaxDay, 1), 31);
            for (int day = 1; day <= max; day++) {
                String url = String.format(Locale.ROOT, "%s/%02d/repo.tar.xz", base, day);
                try {
                    if (!resourceExists(httpClient, url)) {
                        daysMissing++;
                        perDay.add(Map.of("day", day, "status", "无快照", "url", url));
                        continue;
                    }
                    Path relative = Paths.get("demo", year, month, String.format("%02d", day));
                    String stem = String.format(Locale.ROOT, "demo-%s-%s-%02d", year, month, day);
                    List<Path> cers = downloadWithRetrySubdir(url, relative, stem);
                    allCers.addAll(cers);
                    daysOk++;
                    perDay.add(Map.of("day", day, "status", "成功", "cerKept", cers.size(), "url", url));
                } catch (RuntimeException e) {
                    daysFailed++;
                    log.warn("整月 DEMO 第 {} 日失败: {}", day, e.getMessage());
                    perDay.add(Map.of("day", day, "status", "失败", "error", String.valueOf(e.getMessage())));
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("整月下载初始化 HTTP 客户端失败", e);
        }

        lines.add("【统计】成功 " + daysOk + " 天，无文件跳过 " + daysMissing + " 天，失败 " + daysFailed + " 天");
        lines.add("【合计】收集到 .cer 路径 " + allCers.size() + " 个（若每日为同一张信任锚，接口里 savedCount 为去重后条数，见 parsedBeforeDedup）");

        Map<String, List<Path>> filesByRir = new LinkedHashMap<>();
        filesByRir.put("DEMO", allCers);

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("year", year);
        stats.put("month", month);
        stats.put("daysOk", daysOk);
        stats.put("daysMissing", daysMissing);
        stats.put("daysFailed", daysFailed);
        stats.put("cerPathsTotal", allCers.size());
        stats.put("perDay", perDay);

        return DownloadExtractOutcome.builder()
                .filesByRir(filesByRir)
                .summaryLines(lines)
                .mode("demo-month")
                .stats(stats)
                .build();
    }

    private static String[] parseYearMonthFromMonthBase(String base) {
        try {
            URI uri = URI.create(base);
            String path = uri.getPath();
            if (path == null || path.isBlank()) {
                throw new IllegalArgumentException("无法从 URL 解析路径");
            }
            String[] seg = path.replaceAll("/+$", "").split("/");
            if (seg.length < 2) {
                throw new IllegalArgumentException("路径应包含 …/年/月，例如 …/2018/01");
            }
            String month = seg[seg.length - 1];
            String year = seg[seg.length - 2];
            return new String[]{year, month};
        } catch (Exception e) {
            throw new IllegalArgumentException("demo-month-base 格式无效: " + base, e);
        }
    }

    private List<Path> downloadWithRetrySubdir(String url, Path relativeExtract, String archiveStem) {
        RuntimeException failure = null;
        for (int i = 1; i <= retryCount; i++) {
            try {
                log.info("整月 DEMO 尝试下载 attempt={}/{} url={}", i, retryCount, url);
                return downloadAndExtractToPath(url, relativeExtract, archiveStem);
            } catch (Exception e) {
                failure = new RuntimeException(e);
                log.error("整月 DEMO 下载失败 attempt={}", i, e);
            }
        }
        throw new RuntimeException("整月 DEMO 下载失败: " + url, failure);
    }

    private List<Path> downloadAndExtractToPath(String url, Path relativeExtract, String archiveStem) throws IOException {
        Path baseExtract = Paths.get(extractDir).toAbsolutePath().normalize();
        Path outDir = baseExtract.resolve(relativeExtract).normalize();
        if (!outDir.startsWith(baseExtract)) {
            throw new IOException("非法解压目录: " + outDir);
        }
        Files.createDirectories(Paths.get(downloadDir));
        Path archivePath = Paths.get(downloadDir, archiveStem + archiveSuffix(url));
        doDownload(url, archivePath);
        recreateDirectory(outDir);
        log.info("开始解压（大文件可能较久）: {} -> {}", archivePath, outDir);
        extractArchive(archivePath, outDir);
        pruneNonCerFiles(outDir);
        pruneEmptyDirectories(outDir);
        List<Path> cers = scanCerFiles(outDir);
        log.info("解压并清理完成 dir={} 剩余.cer数={}", outDir, cers.size());
        return cers;
    }

    private void pruneNonCerFiles(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> toDelete = walk.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".cer"))
                    .sorted(Comparator.reverseOrder())
                    .collect(Collectors.toList());
            for (Path p : toDelete) {
                Files.deleteIfExists(p);
            }
        }
    }

    private void pruneEmptyDirectories(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder())
                    .filter(p -> !p.equals(root))
                    .filter(Files::isDirectory)
                    .forEach(p -> {
                        try {
                            try (Stream<Path> inner = Files.list(p)) {
                                if (inner.findAny().isEmpty()) {
                                    Files.deleteIfExists(p);
                                }
                            }
                        } catch (IOException ignored) {
                            // 非空或并发删除时忽略
                        }
                    });
        }
    }

    private List<Path> scanCerFiles(Path root) throws IOException {
        if (!Files.exists(root)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".cer"))
                    .collect(Collectors.toList());
        }
    }

    private List<Path> downloadWithRetry(String rir, String url) {
        RuntimeException failure = null;
        for (int i = 1; i <= retryCount; i++) {
            try {
                log.info("开始同步 RIR={}, attempt={}/{}", rir, i, retryCount);
                return downloadAndExtract(rir, url);
            } catch (Exception e) {
                failure = new RuntimeException(e);
                log.error("同步失败 RIR={}, attempt={}", rir, i, e);
            }
        }
        throw new RuntimeException("RIR 同步失败: " + rir, failure);
    }

    private List<Path> downloadAndExtract(String rir, String url) throws IOException {
        Files.createDirectories(Paths.get(downloadDir));
        Files.createDirectories(Paths.get(extractDir));
        Path archivePath = Paths.get(downloadDir, rir.toLowerCase(Locale.ROOT) + "-repo" + archiveSuffix(url));
        doDownload(url, archivePath);
        Path rirExtractPath = Paths.get(extractDir, rir.toLowerCase(Locale.ROOT));
        recreateDirectory(rirExtractPath);
        log.info("RIR={} 下载完成，开始解压（大仓库可能静默数分钟，请稍候）: {} -> {}", rir, archivePath, rirExtractPath);
        extractArchive(archivePath, rirExtractPath);
        log.info("RIR={} 解压完成，开始清理/扫描", rir);
        if ("DEMO".equalsIgnoreCase(rir)) {
            pruneNonCerFiles(rirExtractPath);
            pruneEmptyDirectories(rirExtractPath);
            List<Path> cers = scanCerFiles(rirExtractPath);
            log.info("RIR={} 解压完成（DEMO 已删非 .cer），剩余 .cer 数={}", rir, cers.size());
            return cers;
        }
        List<Path> candidates = scanRpkFiles(rirExtractPath);
        log.info("RIR={} 解压完成，扫描到 roa/crl/mft/cer 文件总数={}", rir, candidates.size());
        return candidates;
    }

    private void doDownload(String url, Path targetPath) throws IOException {
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpGet httpGet = new HttpGet(url);
            httpGet.setHeader("User-Agent", "RPKI-Conflict-Checker/1.0");
            httpClient.execute(httpGet, response -> {
                if (response.getCode() < 200 || response.getCode() >= 300) {
                    throw new IOException("下载失败 HTTP=" + response.getCode() + ", url=" + url);
                }
                HttpEntity entity = response.getEntity();
                if (entity == null) {
                    throw new IOException("下载实体为空: " + url);
                }
                long contentLength = entity.getContentLength();
                try (InputStream in = entity.getContent();
                     OutputStream out = Files.newOutputStream(targetPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    byte[] buffer = new byte[8192];
                    int len;
                    long readBytes = 0;
                    long lastLogBytes = 0;
                    while ((len = in.read(buffer)) != -1) {
                        out.write(buffer, 0, len);
                        readBytes += len;
                        if (readBytes - lastLogBytes >= 5 * 1024 * 1024) {
                            if (contentLength > 0) {
                                long progress = readBytes * 100 / contentLength;
                                log.info("下载进度 {}% ({}/{})", progress, readBytes, contentLength);
                            } else {
                                log.info("已下载 {} bytes", readBytes);
                            }
                            lastLogBytes = readBytes;
                        }
                    }
                    log.info("下载完成: {}, size={} bytes", targetPath, readBytes);
                }
                return null;
            });
        }
    }

    private void extractArchive(Path archive, Path outDir) throws IOException {
        try (InputStream fis = Files.newInputStream(archive);
             BufferedInputStream bis = new BufferedInputStream(fis);
             InputStream compressed = archive.toString().endsWith(".tar.xz")
                     ? new XZCompressorInputStream(bis)
                     : new GzipCompressorInputStream(bis);
             TarArchiveInputStream tar = new TarArchiveInputStream(compressed)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextTarEntry()) != null) {
                String entryName = entry.getName();
                if (entryName.startsWith("./")) {
                    entryName = entryName.substring(2);
                }
                Path target = outDir.resolve(entryName).normalize();
                if (!target.startsWith(outDir)) {
                    continue;
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    try (OutputStream os = Files.newOutputStream(target, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                        tar.transferTo(os);
                    }
                }
            }
        }
    }

    private List<Path> scanRpkFiles(Path root) throws IOException {
        try (var stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.endsWith(".roa")
                                || name.endsWith(".crl")
                                || name.endsWith(".mft")
                                || name.endsWith(".cer");
                    })
                    .collect(Collectors.toList());
        }
    }

    private Map<String, String> buildSources() {
        return Arrays.stream(rirList.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(String::toUpperCase)
                .filter(this::knownRir)
                .collect(Collectors.toMap(rir -> rir, this::effectiveRepoUrl, (a, b) -> a, LinkedHashMap::new));
    }

    private boolean knownRir(String rir) {
        return "RIPE".equals(rir) || "APNIC".equals(rir) || "ARIN".equals(rir) || "DEMO".equals(rir);
    }

    private String talDirOnMirror(String rir) {
        return switch (rir) {
            case "RIPE" -> "ripencc.tal";
            case "APNIC" -> "apnic.tal";
            case "ARIN" -> "arin.tal";
            default -> throw new IllegalArgumentException("未知 RIR: " + rir);
        };
    }

    private String effectiveRepoUrl(String rir) {
        if ("DEMO".equals(rir)) {
            if (demoRepoUrl == null || demoRepoUrl.isBlank()) {
                throw new IllegalStateException("已在 rpki.sync.rirs 中启用 DEMO，请配置 rpki.sources.demo（单日 URL）或 rpki.sources.demo-month-base（整月）");
            }
            return demoRepoUrl.trim();
        }
        if (!autoResolveLatest) {
            return switch (rir) {
                case "RIPE" -> ripeUrl;
                case "APNIC" -> apnicUrl;
                case "ARIN" -> arinUrl;
                default -> throw new IllegalArgumentException(rir);
            };
        }
        try {
            return resolveLatestRepoTarOnMirror(talDirOnMirror(rir));
        } catch (IOException e) {
            throw new RuntimeException("无法解析 " + rir + " 在镜像上的快照地址: " + e.getMessage(), e);
        }
    }

    /**
     * RIPE 统计归档：{@code https://ftp.ripe.net/rpki/<tal>/YYYY/MM/DD/repo.tar.xz}
     */
    private String resolveLatestRepoTarOnMirror(String talDir) throws IOException {
        String base = mirrorBase.endsWith("/") ? mirrorBase : mirrorBase + "/";
        LocalDate end = LocalDate.now(ZoneOffset.UTC);
        int maxDays = 60;
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            for (int i = 0; i < maxDays; i++) {
                LocalDate d = end.minusDays(i);
                String url = String.format(Locale.ROOT, "%s%s/%04d/%02d/%02d/repo.tar.xz",
                        base, talDir, d.getYear(), d.getMonthValue(), d.getDayOfMonth());
                if (resourceExists(httpClient, url)) {
                    log.info("RPKI 归档快照: tal={}, date={}, url={}", talDir, d, url);
                    return url;
                }
            }
        }
        throw new IOException(maxDays + " 日内未找到 " + talDir + " 的 repo.tar.xz；请检查网络或 rpki.sources.mirror-base");
    }

    private boolean resourceExists(CloseableHttpClient httpClient, String url) throws IOException {
        HttpHead head = new HttpHead(url);
        head.setHeader("User-Agent", "RPKI-Conflict-Checker/1.0");
        boolean headOk = Boolean.TRUE.equals(httpClient.execute(head, response -> {
            int code = response.getCode();
            return code >= 200 && code < 300;
        }));
        if (headOk) {
            return true;
        }
        // 部分环境对 HEAD 返回非 2xx，但 GET 正常；用极小 Range 探测以免拖慢自动解析。
        HttpGet probe = new HttpGet(url);
        probe.setHeader("User-Agent", "RPKI-Conflict-Checker/1.0");
        probe.setHeader("Range", "bytes=0-0");
        return Boolean.TRUE.equals(httpClient.execute(probe, response -> {
            int code = response.getCode();
            HttpEntity entity = response.getEntity();
            if (entity != null) {
                EntityUtils.consumeQuietly(entity);
            }
            return (code >= 200 && code < 300) || code == 206;
        }));
    }

    private void recreateDirectory(Path path) throws IOException {
        if (Files.exists(path)) {
            try (var stream = Files.walk(path)) {
                stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            } catch (UncheckedIOException e) {
                throw e.getCause();
            }
        }
        Files.createDirectories(path);
    }

    private String archiveSuffix(String url) {
        if (url.endsWith(".tar.xz")) {
            return ".tar.xz";
        }
        if (url.endsWith(".tar.gz")) {
            return ".tar.gz";
        }
        return ".tar.xz";
    }
}
