package com.rpki.conflictchecker.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 下载解压结果：供接口返回人类可读摘要与原始路径列表。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DownloadExtractOutcome {

    @Builder.Default
    private Map<String, List<Path>> filesByRir = new LinkedHashMap<>();

    /** 中文短句，按顺序展示即可读懂本次任务 */
    private List<String> summaryLines;

    /** demo-month | demo-single | multi-rir | local-cache | local-cache-demo */
    private String mode;

    /** 结构化计数，便于前端或脚本使用 */
    @Builder.Default
    private Map<String, Object> stats = new LinkedHashMap<>();
}
