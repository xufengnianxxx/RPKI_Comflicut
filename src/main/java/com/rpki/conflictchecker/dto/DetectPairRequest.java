package com.rpki.conflictchecker.dto;

import lombok.Data;

/**
 * 前端「双证链下检测」请求体：仅对两证做内存检测，不写库、不触发全量扫描。
 */
@Data
public class DetectPairRequest {
    private Long certIdA;
    private Long certIdB;
}
