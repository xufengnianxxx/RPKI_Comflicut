package com.rpki.conflictchecker.dto;

import lombok.Data;

@Data
public class DetectConflictRequest {
    /**
     * 可选，指定 RIR（如 RIPE/APNIC/ARIN），为空则全量检测。
     */
    private String rir;
}
