package com.rpki.conflictchecker.dto.test;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 单条功能测例运行结果，供报告与 /api/test/functional/report 序列化。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FunctionalTestCaseResultDto {
    private String caseId;
    private String name;
    private String defRef;
    /**
     * 与《冲突规则》形式位对应的备注（见 manifest 的 formalDefNote）。
     */
    private String formalDefNote;
    private String kind;
    /** 本用例涉及证书文件（certs/ 下相对名） */
    private List<String> certFiles;
    private List<String> expectedOfflineTypes;
    /** 链下检测到的全部 conflictType（去重后，有序） */
    private List<String> actualOfflineTypes;
    /**
     * 是否通过：链下结果覆盖全部 expectedOfflineTypes（每条预期类型均在 actual 中出现过）。
     */
    private boolean offlinePass;
    /**
     * 链上结论摘要：verdict、primaryConflictType、fabricMode；MOCK 下与链下无对比关系。
     */
    private String onChainSummary;
    private String onChainVerdict;
    private String onChainPrimaryType;
    /**
     * 与「链下-链上一致」同义；MOCK 恒为 false 或 N/A，由 {@link #onChainNote} 说明。
     */
    private boolean chainConsistentWithOffline;
    private String onChainNote;
    private long durationMs;
}
