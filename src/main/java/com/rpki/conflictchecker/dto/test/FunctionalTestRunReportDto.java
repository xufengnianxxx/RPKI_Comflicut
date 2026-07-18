package com.rpki.conflictchecker.dto.test;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FunctionalTestRunReportDto {
    private Instant runAt;
    private String dataDir;
    private long totalDurationMs;
    private int caseCount;
    private int passedOffline;
    private int failedOffline;
    private String fabricMode;
    private List<FunctionalTestCaseResultDto> cases;
    /**
     * 结论文本模板占位（论文章节可直接引用）。
     */
    private String summaryTemplate;
}
