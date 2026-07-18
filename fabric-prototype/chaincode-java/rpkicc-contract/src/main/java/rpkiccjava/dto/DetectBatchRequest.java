package rpkiccjava.dto;

import rpkiccjava.model.CertificateAsset;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量链上检测请求体（JSON，由 {@link rpkiccjava.RpkiccjavaContract#detectConflictOnChainBatch} 解析）。
 * <ul>
 *   <li>{@code STATE_HASHES}：从世界状态按 certHash 读取证书，做两两组合检测（字典序去重）。</li>
 *   <li>{@code INLINE_ASSETS}：不读账本、不写证书；仅在内存中两两检测（父解析仅限同批内 parentRef 指向的 certHash）。</li>
 * </ul>
 */
public class DetectBatchRequest {

    /** STATE_HASHES（默认）或 INLINE_ASSETS */
    private String mode = "STATE_HASHES";
    private List<String> certHashes = new ArrayList<>();
    private List<CertificateAsset> certificates = new ArrayList<>();

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public List<String> getCertHashes() {
        return certHashes;
    }

    public void setCertHashes(List<String> certHashes) {
        this.certHashes = certHashes;
    }

    public List<CertificateAsset> getCertificates() {
        return certificates;
    }

    public void setCertificates(List<CertificateAsset> certificates) {
        this.certificates = certificates;
    }
}
