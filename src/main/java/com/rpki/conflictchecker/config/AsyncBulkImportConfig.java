package com.rpki.conflictchecker.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 为大批量证书目录导入提供独立线程池，避免占满默认 ForkJoin。
 */
@Configuration
@EnableAsync
public class AsyncBulkImportConfig {

    public static final String BULK_IMPORT_EXECUTOR = "bulkImportExecutor";

    @Bean(name = BULK_IMPORT_EXECUTOR)
    public Executor bulkImportExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(2);
        ex.setMaxPoolSize(4);
        ex.setQueueCapacity(50);
        ex.setThreadNamePrefix("rpki-bulk-");
        ex.initialize();
        return ex;
    }
}
