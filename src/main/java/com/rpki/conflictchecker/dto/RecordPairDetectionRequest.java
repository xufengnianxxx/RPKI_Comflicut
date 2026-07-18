package com.rpki.conflictchecker.dto;

import lombok.Data;

/**
 * 前端在一次「开始检测」完成后上报：链下结论 + 可选链上结论。
 */
@Data
public class RecordPairDetectionRequest {
    private Long certIdA;
    private Long certIdB;
    /** 链下是否检出冲突 */
    private Boolean offlineHasConflict;
    /** 链下说明：无冲突时为「无冲突」，有冲突时为规则与详情摘要 */
    private String offlineSummary;
    private String chainVerdict;
    private String chainPrimaryType;
    /** 链上说明：未执行/失败/无冲突/冲突摘要 */
    private String chainSummary;
}
