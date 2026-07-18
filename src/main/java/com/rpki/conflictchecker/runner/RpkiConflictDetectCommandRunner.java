package com.rpki.conflictchecker.runner;

import com.rpki.conflictchecker.dto.ConflictResult;
import com.rpki.conflictchecker.service.ConflictDetectionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动后自动跑一次全库冲突检测（默认关闭）。配置：
 * <pre>
 * rpki.conflict-detect-on-startup=true
 * </pre>
 */
@Slf4j
@Component
@Order(200)
@ConditionalOnProperty(name = "rpki.conflict-detect-on-startup", havingValue = "true")
public class RpkiConflictDetectCommandRunner implements CommandLineRunner {

    @Autowired
    private ConflictDetectionService conflictDetectionService;

    @Override
    public void run(String... args) {
        log.info("rpki.conflict-detect-on-startup=true，开始全库冲突检测…");
        List<ConflictResult> r = conflictDetectionService.detectAllConflicts();
        log.info("冲突检测结束，去重后命中 {} 条（详见 conflict_record 与 rpki_cert.has_conflict）", r.size());
    }
}
