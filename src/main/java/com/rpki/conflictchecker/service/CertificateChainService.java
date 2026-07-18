package com.rpki.conflictchecker.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.rpki.conflictchecker.entity.RpkiCert;
import com.rpki.conflictchecker.mapper.RpkiCertMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 根据 Authority Key Identifier → Subject Key Identifier 回填 {@code parent_cert_id}（仅当能唯一、可信匹配时）。
 */
@Slf4j
@Service
public class CertificateChainService {

    @Autowired
    private RpkiCertMapper rpkiCertMapper;

    @Transactional
    public void linkParentsForRir(String rir) {
        if (rir == null || rir.isBlank()) {
            return;
        }
        String u = rir.toUpperCase(Locale.ROOT);
        List<RpkiCert> rows = rpkiCertMapper.selectList(new LambdaQueryWrapper<RpkiCert>().eq(RpkiCert::getRir, u));
        if (rows.isEmpty()) {
            return;
        }
        Map<String, List<RpkiCert>> bySki = new HashMap<>();
        for (RpkiCert c : rows) {
            String ski = c.getSubjectKeyId();
            if (ski == null || ski.isBlank()) {
                continue;
            }
            bySki.computeIfAbsent(ski.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(c);
        }
        int updated = 0;
        for (RpkiCert child : rows) {
            String aki = child.getAuthorityKeyId();
            if (aki == null || aki.isBlank()) {
                continue;
            }
            List<RpkiCert> candidates = bySki.get(aki.toLowerCase(Locale.ROOT));
            if (candidates == null || candidates.isEmpty()) {
                continue;
            }
            RpkiCert parent = pickParent(child, candidates);
            if (parent == null || parent.getId() == null || parent.getId().equals(child.getId())) {
                continue;
            }
            if (child.getParentCertId() != null && child.getParentCertId().equals(parent.getId())) {
                continue;
            }
            child.setParentCertId(parent.getId());
            rpkiCertMapper.updateById(child);
            updated++;
        }
        if (updated > 0) {
            log.info("父子链：RIR={} 更新 parent_cert_id {} 条", u, updated);
        }
    }

    private static RpkiCert pickParent(RpkiCert child, List<RpkiCert> candidates) {
        String iss = normalizeDn(child.getIssuer());
        List<RpkiCert> dnMatch = new ArrayList<>();
        for (RpkiCert p : candidates) {
            if (iss != null && iss.equals(normalizeDn(p.getSubject()))) {
                dnMatch.add(p);
            }
        }
        if (dnMatch.size() == 1) {
            return dnMatch.get(0);
        }
        if (dnMatch.size() > 1) {
            return null;
        }
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        return null;
    }

    private static String normalizeDn(String dn) {
        if (dn == null) {
            return null;
        }
        return dn.trim().replaceAll("\\s+", " ");
    }
}
