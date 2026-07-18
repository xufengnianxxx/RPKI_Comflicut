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
@TableName("conflict_record")
public class ConflictRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long certIdA;
    private Long certIdB;
    private String conflictType;
    private String severity;
    private String ruleVersion;
    private String ruleReference;
    private String conflictKey;
    private String details;
    private LocalDateTime detectedAt;

    /** 与 rpki_cert 一致：Fabric 存证成功后回写 */
    private String fabricTxId;
    private String fabricBlockNum;
    private Boolean isSentToFabric;
    private LocalDateTime fabricSendTime;
    private Boolean fabricSendFailed;
    private String fabricLastError;
}
