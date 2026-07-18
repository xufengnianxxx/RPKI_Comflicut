package com.rpki.conflictchecker.config;

import com.rpki.conflictchecker.dto.ConflictResult;
import com.rpki.conflictchecker.service.ConflictDetectionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动后基于库中已有证书做一次冲突检测（不下载、不解析磁盘）。
 * 上链仍通过 {@code POST /cert/push-to-fabric} 按需提交。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "rpki.startup.auto-detect-conflicts", havingValue = "true")
public class RpkiStartupConflictRunner implements ApplicationRunner {

    private final ConflictDetectionService conflictDetectionService;

    public RpkiStartupConflictRunner(ConflictDetectionService conflictDetectionService) {
        this.conflictDetectionService = conflictDetectionService;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            log.info("rpki.startup.auto-detect-conflicts=true，启动时执行冲突检测");
            List<ConflictResult> results = conflictDetectionService.detectAndPersistConflicts(null);
            log.info("启动时冲突检测完成，结果条数={}", results.size());
        } catch (Exception e) {
            log.error("启动时冲突检测失败", e);
        }
    }
}
