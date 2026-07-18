package com.rpki.conflictchecker.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 递归目录批量导入 .cer 的统计与错误明细（供 REST / 异步任务返回）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BulkDirectoryImportResult {

    private String rootPath;
    /** 扫描到的 .cer 路径总数 */
    private long filesDiscovered;
    private long parseSuccess;
    private long parseFailed;
    private long rowsSavedAfterDedup;
    private long durationMs;
    /** 按内容哈希合并写入后，参与父子链回填的 RIR 集合 */
    private List<String> rirsLinked;

    @Builder.Default
    private List<FailureDetail> failures = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FailureDetail {
        private String path;
        private String reason;
    }
}
