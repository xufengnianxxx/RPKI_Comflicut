package rpkiccjava.model;

import org.hyperledger.fabric.contract.annotation.DataType;
import org.hyperledger.fabric.contract.annotation.Property;

import java.util.ArrayList;
import java.util.List;

/**
 * 链上脱敏证书资产（不上链原始 PEM、完整 DN）。
 * <p>{@link DataType} 供 Fabric Contract 元数据生成；序列化仍使用 JSON（Gson）写入世界状态。</p>
 */
@DataType
public class CertificateAsset {

    @Property
    private String certHash;
    @Property
    private String rir;
    @Property
    private String certNameShort;
    @Property
    private List<String> ipv4Prefixes = new ArrayList<>();
    @Property
    private List<String> ipv6Prefixes = new ArrayList<>();
    @Property
    private List<String> asNumbers = new ArrayList<>();
    /**
     * 父证书 cert_hash 或链下标识字符串；解析父资产时优先按 certHash 键查找。
     */
    @Property
    private String parentRef;
    /** 链下 parent_cert_id 数值（可选，便于与链下库对齐） */
    @Property
    private Long parentCertId;
    @Property
    private boolean revoked;
    /**
     * 写入链上的逻辑时间戳（毫秒，Unix epoch）。未设置时由 {@code storeCertificateBatch} 填当前提交时间。
     */
    @Property
    private long timestamp;

    public String getCertHash() {
        return certHash;
    }

    public void setCertHash(String certHash) {
        this.certHash = certHash;
    }

    public String getRir() {
        return rir;
    }

    public void setRir(String rir) {
        this.rir = rir;
    }

    public String getCertNameShort() {
        return certNameShort;
    }

    public void setCertNameShort(String certNameShort) {
        this.certNameShort = certNameShort;
    }

    public List<String> getIpv4Prefixes() {
        return ipv4Prefixes;
    }

    public void setIpv4Prefixes(List<String> ipv4Prefixes) {
        this.ipv4Prefixes = ipv4Prefixes;
    }

    public List<String> getIpv6Prefixes() {
        return ipv6Prefixes;
    }

    public void setIpv6Prefixes(List<String> ipv6Prefixes) {
        this.ipv6Prefixes = ipv6Prefixes;
    }

    public List<String> getAsNumbers() {
        return asNumbers;
    }

    public void setAsNumbers(List<String> asNumbers) {
        this.asNumbers = asNumbers;
    }

    public String getParentRef() {
        return parentRef;
    }

    public void setParentRef(String parentRef) {
        this.parentRef = parentRef;
    }

    public Long getParentCertId() {
        return parentCertId;
    }

    public void setParentCertId(Long parentCertId) {
        this.parentCertId = parentCertId;
    }

    public boolean isRevoked() {
        return revoked;
    }

    public void setRevoked(boolean revoked) {
        this.revoked = revoked;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
}
