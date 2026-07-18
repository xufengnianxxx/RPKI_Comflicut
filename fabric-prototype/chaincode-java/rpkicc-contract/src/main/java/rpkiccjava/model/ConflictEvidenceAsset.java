package rpkiccjava.model;

import org.hyperledger.fabric.contract.annotation.DataType;
import org.hyperledger.fabric.contract.annotation.Property;

import java.util.ArrayList;
import java.util.List;

/**
 * 链上冲突证据资产。
 * <p>{@code conflictId} 与 {@code conflictKey} 二选一或同时提供：世界状态键为 {@code CONFLICT|&lt;id&gt;}，
 * 若仅提供 {@code conflictKey} 则以其为 id。</p>
 * <p>{@code conflictType} 存 {@link ConflictType} 名字符串或链下扩展类型名。</p>
 */
@DataType
public class ConflictEvidenceAsset {

    /** 业务侧稳定主键（推荐）；与 conflictKey 等价优先 */
    @Property
    private String conflictId;
    @Property
    private String conflictKey;
    @Property
    private List<String> involvedCertHashes = new ArrayList<>();
    @Property
    private String certHashA;
    @Property
    private String certHashB;
    /** {@link ConflictType#name()} 或兼容旧字符串 */
    @Property
    private String conflictType;
    @Property
    private String conflictDetails;
    @Property
    private String detailsShort;
    @Property
    private String severity;
    @Property
    private String ruleReference;
    /** 检测到冲突的 UTC 毫秒时间戳 */
    @Property
    private long detectedAt;

    public String getConflictId() {
        return conflictId;
    }

    public void setConflictId(String conflictId) {
        this.conflictId = conflictId;
    }

    public String getConflictKey() {
        return conflictKey;
    }

    public void setConflictKey(String conflictKey) {
        this.conflictKey = conflictKey;
    }

    public List<String> getInvolvedCertHashes() {
        return involvedCertHashes;
    }

    public void setInvolvedCertHashes(List<String> involvedCertHashes) {
        this.involvedCertHashes = involvedCertHashes;
    }

    public String getCertHashA() {
        return certHashA;
    }

    public void setCertHashA(String certHashA) {
        this.certHashA = certHashA;
    }

    public String getCertHashB() {
        return certHashB;
    }

    public void setCertHashB(String certHashB) {
        this.certHashB = certHashB;
    }

    public String getConflictType() {
        return conflictType;
    }

    public void setConflictType(String conflictType) {
        this.conflictType = conflictType;
    }

    public String getConflictDetails() {
        return conflictDetails;
    }

    public void setConflictDetails(String conflictDetails) {
        this.conflictDetails = conflictDetails;
    }

    public String getDetailsShort() {
        return detailsShort;
    }

    public void setDetailsShort(String detailsShort) {
        this.detailsShort = detailsShort;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public String getRuleReference() {
        return ruleReference;
    }

    public void setRuleReference(String ruleReference) {
        this.ruleReference = ruleReference;
    }

    public long getDetectedAt() {
        return detectedAt;
    }

    public void setDetectedAt(long detectedAt) {
        this.detectedAt = detectedAt;
    }
}
