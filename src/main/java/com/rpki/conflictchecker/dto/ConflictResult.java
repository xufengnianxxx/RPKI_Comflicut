package com.rpki.conflictchecker.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConflictResult {
    private Long certIdA;
    private Long certIdB;
    private String conflictType;
    private String severity;
    private String ruleVersion;
    private String details;
    private String ruleReference;
}
