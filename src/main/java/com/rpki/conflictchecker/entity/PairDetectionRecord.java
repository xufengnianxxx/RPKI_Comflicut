package com.rpki.conflictchecker.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("pair_detection_record")
public class PairDetectionRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long certIdA;
    private Long certIdB;
    private Boolean offlineHasConflict;
    private String offlineSummary;
    private String chainVerdict;
    private String chainPrimaryType;
    private String chainSummary;
    private LocalDateTime createdAt;
}
