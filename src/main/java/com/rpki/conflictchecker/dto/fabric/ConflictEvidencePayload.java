package com.rpki.conflictchecker.dto.fabric;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 与链码 {@code ConflictEvidenceAsset} 对齐，用于链下权威结果上链存证。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConflictEvidencePayload {

    private String conflictKey;
    /** 与链码 conflictId 一致；未填时可由 conflictKey 代替 */
    private String conflictId;
    /** 多证书参与时优先使用；与 certHashA/B 可并存 */
    private List<String> involvedCertHashes;
    private String certHashA;
    private String certHashB;
    private String conflictType;
    private String severity;
    private String ruleReference;
    /** 结构化说明（链上建议控制长度） */
    private String conflictDetails;
    /** 链下 details 可能较长，链上存摘要；完整内容在 conflict_record.details */
    private String detailsShort;
    /** 检测到冲突的 UTC 毫秒时间戳（与链码 detectedAt 对齐） */
    private long detectedAt;
}
