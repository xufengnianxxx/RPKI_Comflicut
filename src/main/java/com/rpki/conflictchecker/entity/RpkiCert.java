package com.rpki.conflictchecker.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("rpki_cert")
public class RpkiCert {

    @TableId(type = IdType.AUTO)
    private Long id;

    // 证书信息
    private String certName;
    /** 磁盘上 .cer 文件名，如 -4Xgjl27Bclbe9Dafl_m_OOYJv8.cer */
    private String cerFileName;
    private String serialNumber;
    private String issuer;
    private String subject;
    private String rir;
    private LocalDateTime notBefore;
    private LocalDateTime notAfter;

    // RFC 3779 资源
    private String ipv4Prefixes;
    private String ipv6Prefixes;
    private String asNumbers;

    // 证书链信息（SKI/AKI 来自扩展，用于父子匹配）
    private String subjectKeyId;
    private String authorityKeyId;
    private Long parentCertId;
    private Boolean revoked;

    // 自冲突检测结果
    private Boolean hasConflict;
    private String conflictDetails;

    // Fabric 上链信息
    private String fabricTxId;
    private String fabricBlockNum;
    private Boolean isSentToFabric;
    private LocalDateTime fabricSendTime;
    /** 存证提交最终失败（重试耗尽）时为 true；链下检测结论 {@link #hasConflict} 不受影响 */
    private Boolean fabricSendFailed;
    /** 最近一次上链失败的简短原因，供运维与审计 */
    private String fabricLastError;

    // 存证信息
    private String certHash;
    private String rawCertData;

    // 时间戳
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
