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
@TableName("fabric_tx_record")
public class FabricTxRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long conflictRecordId;
    private String mode;
    private String txId;
    private String blockNum;
    private String payload;
    private String status;
    private String errorMessage;
    /** 同一轮「链下检测 → 上链存证」关联 ID，便于审计串联多条 fabric_tx_record */
    private String correlationId;
    /** 流水线阶段，如 CERT_BATCH、CONFLICT_BATCH、CHAIN_DETECT_ANCHOR */
    private String pipelineStep;
    private LocalDateTime createdAt;
}
