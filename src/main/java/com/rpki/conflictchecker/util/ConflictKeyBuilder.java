package com.rpki.conflictchecker.util;

import com.rpki.conflictchecker.dto.ConflictResult;

/**
 * 与 {@code RpkiUnifiedConflictDetectionService} 中 conflict_record.conflict_key 生成规则一致。
 */
public final class ConflictKeyBuilder {

    private ConflictKeyBuilder() {
    }

    public static String build(Long certIdA, Long certIdB, String conflictType, String details) {
        long min = Math.min(certIdA == null ? 0 : certIdA, certIdB == null ? 0 : certIdB);
        long max = Math.max(certIdA == null ? 0 : certIdA, certIdB == null ? 0 : certIdB);
        return min + "|" + max + "|" + conflictType + "|" + details;
    }

    public static String build(ConflictResult r) {
        if (r == null) {
            return "";
        }
        return build(r.getCertIdA(), r.getCertIdB(), r.getConflictType(), r.getDetails());
    }

    /**
     * 与链码世界状态键片段对齐（仅保留安全字符），{@code conflict_record.conflict_key} 应与此一致以便回写 fabric 字段。
     */
    public static String toLedgerKey(String rawKey) {
        if (rawKey == null) {
            return "";
        }
        return rawKey.replaceAll("[^a-zA-Z0-9._|@-]", "_");
    }

    public static String toLedgerKey(ConflictResult r) {
        return toLedgerKey(build(r));
    }
}
