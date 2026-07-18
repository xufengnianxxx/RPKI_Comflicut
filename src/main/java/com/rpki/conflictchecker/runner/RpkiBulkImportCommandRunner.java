package com.rpki.conflictchecker.runner;

import com.rpki.conflictchecker.dto.BulkDirectoryImportResult;
import com.rpki.conflictchecker.service.RpkiBulkDirectoryImportService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.nio.file.Paths;

/**
 * 启动时一次性导入（默认关闭）。需同时设置 {@code rpki.bulk-import.enabled=true} 与有效 {@code root}：
 * <pre>
 * rpki.bulk-import.enabled=true
 * rpki.bulk-import.root=/path/to/.../out
 * rpki.bulk-import.rir=AFRINIC   # 可选
 * rpki.bulk-import.batch-size=400
 * </pre>
 */
@Slf4j
@Component
@Order(100)
@ConditionalOnProperty(prefix = "rpki.bulk-import", name = "enabled", havingValue = "true")
public class RpkiBulkImportCommandRunner implements CommandLineRunner {

    @Value("${rpki.bulk-import.root}")
    private String rootPath;

    @Value("${rpki.bulk-import.rir:}")
    private String rirOverride;

    @Value("${rpki.bulk-import.batch-size:400}")
    private int batchSize;

    @Autowired
    private RpkiBulkDirectoryImportService bulkDirectoryImportService;

    @Override
    public void run(String... args) {
        if (rootPath == null || rootPath.isBlank()) {
            log.warn("rpki.bulk-import.enabled=true 但 root 为空，跳过导入");
            return;
        }
        log.info("rpki.bulk-import 启动导入 root={} rir={} batchSize={}", rootPath, rirOverride, batchSize);
        BulkDirectoryImportResult r = bulkDirectoryImportService.importDirectory(
                Paths.get(rootPath),
                rirOverride.isBlank() ? null : rirOverride,
                batchSize);
        log.info("rpki.bulk-import 完成 discovered={} ok={} fail={} savedRows={} ms={}",
                r.getFilesDiscovered(), r.getParseSuccess(), r.getParseFailed(),
                r.getRowsSavedAfterDedup(), r.getDurationMs());
    }
}
