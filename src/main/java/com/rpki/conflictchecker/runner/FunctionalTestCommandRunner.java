package com.rpki.conflictchecker.runner;

import com.rpki.conflictchecker.service.FunctionalTestService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 启动参数 {@code --test-mode=functional} 时，在应用就绪后跑一遍与 POST /test/functional 相同的逻辑，便于 CI/脚本无 HTTP。
 * 需同时开启 {@code rpki.test.functional-cli=true}，避免与日常启动冲突。
 */
@Slf4j
@Component
@Order(150)
@ConditionalOnProperty(name = "rpki.test.functional-cli", havingValue = "true")
public class FunctionalTestCommandRunner implements ApplicationRunner {

    @Autowired
    private FunctionalTestService functionalTestService;

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption("test-mode")) {
            return;
        }
        String v = firstOpt(args, "test-mode");
        if (v == null || !"functional".equalsIgnoreCase(v.trim())) {
            return;
        }
        try {
            log.info("test-mode=functional 已设置，开始执行功能测试集…");
            var r = functionalTestService.runAll();
            log.info("功能测试完成：链下通过 {}/{}，总耗时 {} ms",
                    r.getPassedOffline(), r.getCaseCount(), r.getTotalDurationMs());
        } catch (Exception e) {
            log.error("功能测试执行失败: {}", e.getMessage(), e);
        }
    }

    private static String firstOpt(ApplicationArguments args, String name) {
        for (String s : args.getOptionValues(name)) {
            if (s != null && !s.isEmpty()) {
                return s;
            }
        }
        return null;
    }
}
