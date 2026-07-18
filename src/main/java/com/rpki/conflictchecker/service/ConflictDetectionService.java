package com.rpki.conflictchecker.service;

import com.rpki.conflictchecker.dto.ConflictResult;
import com.rpki.conflictchecker.entity.RpkiCert;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 兼容入口：将冲突检测委托给 {@link RpkiUnifiedConflictDetectionService}（RFC 3779/6487/6811 统一实现）。
 */
@Slf4j
@Service
public class ConflictDetectionService {

    @Autowired
    private RpkiUnifiedConflictDetectionService unifiedConflictDetectionService;

    /**
     * 按 RIR 过滤（可选）并持久化冲突结果；{@code rir == null} 表示全库。
     */
    public List<ConflictResult> detectAndPersistConflicts(String rir) {
        return unifiedConflictDetectionService.detectAndPersistConflicts(rir);
    }

    /**
     * 全库检测并落库（跨 RIR / 跨目录）。
     */
    public List<ConflictResult> detectAllConflicts() {
        return unifiedConflictDetectionService.detectAllConflicts();
    }

    /**
     * 对内存中的证书子集做检测（不写库、不重置标记），供测试或自定义流水线。
     */
    public List<ConflictResult> detect(List<RpkiCert> certs) {
        return unifiedConflictDetectionService.detectInMemory(certs);
    }

    /**
     * 双证检测并持久化到 conflict_record / rpki_cert 冲突摘要。
     */
    public List<ConflictResult> detectPairAndPersist(Long certIdA, Long certIdB) {
        return unifiedConflictDetectionService.detectPairAndPersist(certIdA, certIdB);
    }
}
