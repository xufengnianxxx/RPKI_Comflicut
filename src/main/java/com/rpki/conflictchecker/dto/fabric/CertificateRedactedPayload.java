package com.rpki.conflictchecker.dto.fabric;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 与链码 {@code CertificateAsset} 字段对齐的脱敏载荷（Spring → Gson → Fabric）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CertificateRedactedPayload {

    private String certHash;
    private String rir;
    private String certNameShort;
    @Builder.Default
    private List<String> ipv4Prefixes = new ArrayList<>();
    @Builder.Default
    private List<String> ipv6Prefixes = new ArrayList<>();
    @Builder.Default
    private List<String> asNumbers = new ArrayList<>();
    /** 链下 parent_cert_id 或父 cert_hash 字符串 */
    private String parentRef;
    /** 链下 parent_cert_id 数值（与链码 parentCertId 对齐，可选） */
    private Long parentCertId;
    private boolean revoked;
    /** 上链逻辑时间戳（毫秒）；0 表示由链码在 store 时填充 */
    private long timestamp;
}
