package com.rpki.conflictchecker.dto.fabric;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 与链码 {@code CertificateAsset} 对齐的脱敏载荷（不上链 raw、完整 DN）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CertificateRedactedDto {
    private String certHash;
    private String rir;
    private String certNameShort;
    @Builder.Default
    private List<String> ipv4Prefixes = new ArrayList<>();
    @Builder.Default
    private List<String> ipv6Prefixes = new ArrayList<>();
    @Builder.Default
    private List<String> asNumbers = new ArrayList<>();
    /** 链下 parent_cert_id 字符串化，或父 cert_hash */
    private String parentRef;
    private boolean revoked;
}
