package com.rpki.conflictchecker.dto.fabric;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 与链码 {@code detectConflictOnChain} / {@code detectConflictOnChainBatch} 返回 JSON 对齐，供 Gson 解析。
 */
@Data
public class ChaincodeConflictDetectResult {

    private String txId;
    /** CONFLICT 或 NO_CONFLICT */
    private String verdict;
    /** 链码枚举名，如 PREFIX_OVERLAP、INHERITANCE_VIOLATION */
    private String primaryConflictType;
    private String legacyWorstCode;
    private List<FindingEntry> findings = new ArrayList<>();
    private List<String> rawFindings = new ArrayList<>();
    private List<String> log = new ArrayList<>();
    private String persistedEvidenceKey;
    private List<String> persistedEvidenceKeys = new ArrayList<>();
    private List<String> involvedCertHashes = new ArrayList<>();
    private int pairsExamined;
    private int conflictingPairs;

    @Data
    public static class FindingEntry {
        private String type;
        private String detail;
        private String certHashA;
        private String certHashB;
        /** 与链码 findings.conflictScope 对齐 */
        private String conflictScope;
        /** 继承类 finding 时的子证书 hash */
        private String involvedCertHashChild;
        /** INHERIT / AS / IPV4 / IPV6 / MISSING */
        private String rulePhase;
    }
}
