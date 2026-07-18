package com.rpki.conflictchecker.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RpkCertificateDTO {
    private String filePath;
    /** 从 filePath 解析出的 .cer 文件名 */
    private String cerFileName;
    private String certName;
    private String serialNumber;
    private String issuer;
    private String subject;
    private LocalDateTime notBefore;
    private LocalDateTime notAfter;
    private List<String> ipv4Prefixes;
    private List<String> ipv6Prefixes;
    private List<String> asNumbers;
    private String certHash;
    private boolean revoked;
    /** Subject Key Identifier，hex 小写 */
    private String subjectKeyId;
    /** Authority Key Identifier，hex 小写 */
    private String authorityKeyId;
}
