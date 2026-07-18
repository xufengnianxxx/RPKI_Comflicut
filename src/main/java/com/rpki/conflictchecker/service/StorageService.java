package com.rpki.conflictchecker.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.rpki.conflictchecker.entity.ConflictRecord;
import com.rpki.conflictchecker.entity.FabricTxRecord;
import com.rpki.conflictchecker.entity.PairDetectionRecord;
import com.rpki.conflictchecker.entity.RpkiCert;
import com.rpki.conflictchecker.mapper.ConflictRecordMapper;
import com.rpki.conflictchecker.mapper.FabricTxRecordMapper;
import com.rpki.conflictchecker.mapper.PairDetectionRecordMapper;
import com.rpki.conflictchecker.mapper.RpkiCertMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
public class StorageService {

    @Autowired
    private RpkiCertMapper rpkiCertMapper;

    @Autowired
    private ConflictRecordMapper conflictRecordMapper;

    @Autowired
    private FabricTxRecordMapper fabricTxRecordMapper;

    @Autowired
    private PairDetectionRecordMapper pairDetectionRecordMapper;

    @Transactional
    public void saveOrUpdateCertificatesBatch(List<RpkiCert> certs) {
        if (certs == null || certs.isEmpty()) {
            return;
        }
        int total = certs.size();
        log.info("开始批量写入证书，条数={}", total);

        List<RpkiCert> withHash = new ArrayList<>();
        List<RpkiCert> noHash = new ArrayList<>();
        for (RpkiCert c : certs) {
            if (c.getCertHash() != null && !c.getCertHash().isBlank()) {
                withHash.add(c);
            } else {
                noHash.add(c);
            }
        }

        Map<String, Long> hashToId = new HashMap<>();
        List<String> distinctHashes = withHash.stream().map(RpkiCert::getCertHash).distinct().toList();
        final int inChunk = 800;
        for (int i = 0; i < distinctHashes.size(); i += inChunk) {
            List<String> chunk = distinctHashes.subList(i, Math.min(i + inChunk, distinctHashes.size()));
            List<RpkiCert> rows = rpkiCertMapper.selectList(
                    new LambdaQueryWrapper<RpkiCert>().in(RpkiCert::getCertHash, chunk));
            for (RpkiCert row : rows) {
                hashToId.put(row.getCertHash(), row.getId());
            }
        }

        int done = 0;
        for (RpkiCert cert : withHash) {
            done++;
            if (done % 400 == 0 || done == withHash.size()) {
                log.info("证书入库进度 {}/{}（按哈希 upsert）", done, withHash.size());
            }
            cert.setUpdatedAt(LocalDateTime.now());
            Long id = hashToId.get(cert.getCertHash());
            if (id != null) {
                cert.setId(id);
                rpkiCertMapper.updateById(cert);
            } else {
                cert.setCreatedAt(LocalDateTime.now());
                rpkiCertMapper.insert(cert);
                if (cert.getId() != null) {
                    hashToId.put(cert.getCertHash(), cert.getId());
                }
            }
        }

        for (RpkiCert cert : noHash) {
            cert.setUpdatedAt(LocalDateTime.now());
            cert.setCreatedAt(LocalDateTime.now());
            rpkiCertMapper.insert(cert);
        }
        if (!noHash.isEmpty()) {
            log.info("已写入无 certHash 的证书 {} 条", noHash.size());
        }
        log.info("批量写入证书完成，共 {} 条", total);
    }

    public Page<ConflictRecord> pageConflictRecords(long current, long size) {
        Page<ConflictRecord> page = new Page<>(current, size);
        return conflictRecordMapper.selectPage(page, new LambdaQueryWrapper<ConflictRecord>()
                .orderByDesc(ConflictRecord::getDetectedAt));
    }

    public Page<PairDetectionRecord> pagePairDetectionRecords(long current, long size) {
        Page<PairDetectionRecord> page = new Page<>(current, size);
        return pairDetectionRecordMapper.selectPage(page, new LambdaQueryWrapper<PairDetectionRecord>()
                .orderByDesc(PairDetectionRecord::getCreatedAt));
    }

    public void savePairDetectionRecord(PairDetectionRecord record) {
        if (record.getCreatedAt() == null) {
            record.setCreatedAt(LocalDateTime.now());
        }
        pairDetectionRecordMapper.insert(record);
    }

    public Page<RpkiCert> pageCertificates(long current, long size, String rir, Boolean hasConflict, String keyword) {
        Page<RpkiCert> page = new Page<>(current, size);
        LambdaQueryWrapper<RpkiCert> q = new LambdaQueryWrapper<RpkiCert>()
                .orderByDesc(RpkiCert::getUpdatedAt)
                .orderByDesc(RpkiCert::getId);
        if (rir != null && !rir.isBlank()) {
            q.eq(RpkiCert::getRir, rir.toUpperCase(Locale.ROOT));
        }
        if (hasConflict != null) {
            q.eq(RpkiCert::getHasConflict, hasConflict);
        }
        if (keyword != null && !keyword.isBlank()) {
            String k = keyword.trim();
            String like = "%" + k + "%";
            q.and(w -> w.like(RpkiCert::getCertHash, like)
                    .or().apply("CAST(id AS CHAR) LIKE {0}", like));
        }
        return rpkiCertMapper.selectPage(page, q);
    }

    public RpkiCert getCertDetail(Long id) {
        return rpkiCertMapper.selectById(id);
    }

    public List<ConflictRecord> listConflictsByCertId(Long certId) {
        return conflictRecordMapper.selectList(new LambdaQueryWrapper<ConflictRecord>()
                .and(w -> w.eq(ConflictRecord::getCertIdA, certId).or().eq(ConflictRecord::getCertIdB, certId))
                .orderByDesc(ConflictRecord::getDetectedAt));
    }

    public ConflictRecord getConflictById(Long id) {
        return conflictRecordMapper.selectById(id);
    }

    @Transactional
    public void saveFabricTx(FabricTxRecord record) {
        fabricTxRecordMapper.insert(record);
    }

    public List<RpkiCert> findAllByRir(String rir) {
        if (rir == null || rir.isBlank()) {
            return rpkiCertMapper.selectList(null);
        }
        return rpkiCertMapper.selectList(new LambdaQueryWrapper<RpkiCert>().eq(RpkiCert::getRir, rir.toUpperCase()));
    }

    public long countCertificates() {
        return rpkiCertMapper.selectCount(null);
    }
}
