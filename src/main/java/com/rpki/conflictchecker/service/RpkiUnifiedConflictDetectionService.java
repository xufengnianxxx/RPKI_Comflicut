package com.rpki.conflictchecker.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.rpki.conflictchecker.dto.ConflictResult;
import com.rpki.conflictchecker.entity.ConflictRecord;
import com.rpki.conflictchecker.entity.RpkiCert;
import com.rpki.conflictchecker.mapper.ConflictRecordMapper;
import com.rpki.conflictchecker.mapper.RpkiCertMapper;
import com.rpki.conflictchecker.service.fabric.FabricConflictAnchorService;
import com.rpki.conflictchecker.util.ConflictKeyBuilder;
import com.rpki.conflictchecker.util.IPAddressUtils;
import com.rpki.conflictchecker.util.Ipv6CidrUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Type;
import java.math.BigInteger;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * RPKI 资源冲突统一检测（RFC 3779 / RFC 6487 / RFC 6811 思路），支持：
 * <ul>
 *   <li>IPv4/IPv6：父子越权、同级重叠/包含、相邻同长前缀边界</li>
 *   <li>AS：父子越权、同级重复授权；基于证书资源的「IP 重叠且 AS 集合不相交」启发式（近似 ROA 起源不一致）</li>
 *   <li>继承链：多跳祖先撤销残留、沿 parent_cert_id 递推的资源包含关系</li>
 * </ul>
 * 默认仅检测<strong>当前时刻有效</strong>的证书（UTC）。父子越权仅认 {@code parent_cert_id}（可选 AKI/SKI 一致性）；
 * 对等 IP/AS 冲突默认限定在<strong>同 RIR 且同签发者或同父节点</strong>，避免仅凭地址空间碰撞误报。
 */
@Slf4j
@Service
public class RpkiUnifiedConflictDetectionService {

    private static final Gson GSON = new GsonBuilder().create();
    private static final Type LIST_STRING = new TypeToken<List<String>>() {}.getType();

    private static final String RULE_VERSION = "v2.0-unified";

    @Autowired
    private RpkiCertMapper rpkiCertMapper;

    @Autowired
    private ConflictRecordMapper conflictRecordMapper;

    @Autowired
    private FabricConflictAnchorService fabricConflictAnchorService;

    /**
     * 若为 true，则「子 ⊆ 父」资源检测仍使用 issuer/subject 字符串回退判定父子（易把无关两张证配成父子，导致几乎全是误报「子超出父授权」）。
     * 默认 false：仅当 {@code child.parent_cert_id == parent.id} 时才做 IP/AS 越权与直接父子撤销检测。
     */
    @Value("${rpki.conflict.use-dn-fallback-for-resource-parent:false}")
    private boolean useDnFallbackForResourceParent;

    /**
     * 对等证书之间的 IP/AS 重叠、相邻前缀、IP-AS 启发式：默认仅在「同一 RIR 且（同一签发者 DN 或同一 parent_cert_id）」下比较，
     * 符合「同一签发树下同级 EE/CA 资源是否矛盾」的语义；false 则退回「仅 IPv4 首字节桶」的宽松候选（易误报）。
     */
    @Value("${rpki.conflict.restrict-peer-conflicts:true}")
    private boolean restrictPeerConflicts;

    /**
     * 当子、父均存在 AKI/SKI 时，要求 {@code child.authorityKeyId} 与 {@code parent.subjectKeyId} 一致才承认委托链，否则忽略该 parent_cert_id 的越权判定（防错链）。
     */
    @Value("${rpki.conflict.require-aki-ski-for-delegated-scope:true}")
    private boolean requireAkiSkiForDelegatedScope;

    /**
     * 全库检测并落库（不限 RIR，跨 member_repository / validated 等目录语义）。
     */
    public List<ConflictResult> detectAllConflicts() {
        return detectAndPersistConflicts(null);
    }

    /**
     * 仅内存检测：不重置 {@code has_conflict}、不写 {@code conflict_record}、不更新库。
     */
    public List<ConflictResult> detectInMemory(List<RpkiCert> certs) {
        if (certs == null || certs.isEmpty()) {
            return List.of();
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<RpkiCert> validNow = filterValidAt(certs, now);
        Map<Long, RpkiCert> byId = validNow.stream()
                .filter(c -> c.getId() != null)
                .collect(Collectors.toMap(RpkiCert::getId, c -> c, (a, b) -> a));
        List<ConflictResult> raw = new ArrayList<>();
        detectMultiHopChain(validNow, byId, now, raw);
        detectPairwiseIndexed(validNow, byId, raw);
        return dedup(raw);
    }

    /**
     * 双证检测并持久化：仅针对两张证书写入 conflict_record，并更新两证的 has_conflict/conflict_details。
     * 不执行全库 resetConflictFlags，避免影响其他证书既有状态。
     */
    @Transactional
    public List<ConflictResult> detectPairAndPersist(Long certIdA, Long certIdB) {
        if (certIdA == null || certIdB == null) {
            throw new IllegalArgumentException("certIdA 与 certIdB 均不能为空");
        }
        if (certIdA.equals(certIdB)) {
            throw new IllegalArgumentException("两个证书 ID 不能相同");
        }
        RpkiCert a = rpkiCertMapper.selectById(certIdA);
        RpkiCert b = rpkiCertMapper.selectById(certIdB);
        if (a == null || b == null) {
            throw new IllegalArgumentException("证书不存在：请检查 ID 是否正确");
        }

        List<ConflictResult> deduped = detectInMemory(List.of(a, b)).stream()
                .filter(r -> {
                    long ra = r.getCertIdA() == null ? -1 : r.getCertIdA();
                    long rb = r.getCertIdB() == null ? -1 : r.getCertIdB();
                    long mn = Math.min(ra, rb);
                    long mx = Math.max(ra, rb);
                    long id1 = Math.min(certIdA, certIdB);
                    long id2 = Math.max(certIdA, certIdB);
                    return mn == id1 && mx == id2;
                })
                .collect(Collectors.toList());

        saveConflictRecords(deduped);
        updateCertConflictSummary(deduped);
        return deduped;
    }

    /**
     * 可选按 RIR 过滤后检测并落库；{@code rir == null} 表示全量。
     */
    public List<ConflictResult> detectAndPersistConflicts(String rir) {
        long t0 = System.currentTimeMillis();
        List<RpkiCert> all = loadCerts(rir);
        log.info("[RPKI-冲突] 加载证书 {} 条（rirFilter={}）", all.size(), rir == null ? "ALL" : rir);
        log.info("[RPKI-冲突] 父子资源判定: {}；对等冲突范围: {}；AKI/SKI 校验: {}",
                useDnFallbackForResourceParent ? "issuer/subject 回退（调试用）" : "parent_cert_id",
                restrictPeerConflicts ? "同RIR+(同issuer或同parent_cert_id)" : "IPv4桶内任意对",
                requireAkiSkiForDelegatedScope ? "双端有值则须匹配" : "关闭");

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<RpkiCert> validNow = filterValidAt(all, now);
        log.info("[RPKI-冲突] 当前 UTC 有效证书 {} 条", validNow.size());

        Map<Long, RpkiCert> byId = validNow.stream()
                .filter(c -> c.getId() != null)
                .collect(Collectors.toMap(RpkiCert::getId, c -> c, (a, b) -> a));

        resetConflictFlags(all);

        List<ConflictResult> raw = new ArrayList<>();

        log.info("[RPKI-冲突] 模块1：继承链多跳检测（撤销残留 + 递推资源包含）…");
        detectMultiHopChain(validNow, byId, now, raw);

        log.info("[RPKI-冲突] 模块2：候选对 IPv4/IPv6/AS 成对检测…");
        int pairCount = detectPairwiseIndexed(validNow, byId, raw);

        log.info("[RPKI-冲突] 原始命中 {} 条，候选对约 {} 对，开始去重…", raw.size(), pairCount);
        List<ConflictResult> deduped = dedup(raw);
        logStats(deduped, t0, validNow.size(), pairCount);

        saveConflictRecords(deduped);
        updateCertConflictSummary(deduped);
        fabricConflictAnchorService.anchorAfterDetectionIfEnabled(deduped, byId);

        log.info("[RPKI-冲突] 完成，去重后 {} 条，耗时 {} ms", deduped.size(), System.currentTimeMillis() - t0);
        return deduped;
    }

    private List<RpkiCert> loadCerts(String rir) {
        if (rir == null || rir.isBlank()) {
            return rpkiCertMapper.selectList(null);
        }
        return rpkiCertMapper.selectList(new LambdaQueryWrapper<RpkiCert>()
                .eq(RpkiCert::getRir, rir.toUpperCase(Locale.ROOT)));
    }

    private static List<RpkiCert> filterValidAt(List<RpkiCert> certs, LocalDateTime now) {
        List<RpkiCert> out = new ArrayList<>();
        for (RpkiCert c : certs) {
            if (c.getNotBefore() == null || c.getNotAfter() == null) {
                out.add(c);
                continue;
            }
            if (!now.isBefore(c.getNotBefore()) && !now.isAfter(c.getNotAfter())) {
                out.add(c);
            }
        }
        return out;
    }

    /**
     * 沿 {@code parent_cert_id} 向上构造链 [leaf, parent, grandparent, …]，检测任一祖先已撤销而叶子仍标记为未撤销。
     */
    private void detectMultiHopChain(List<RpkiCert> valid, Map<Long, RpkiCert> byId, LocalDateTime ignored, List<ConflictResult> out) {
        for (RpkiCert leaf : valid) {
            if (leaf.getId() == null) {
                continue;
            }
            List<RpkiCert> chainUp = walkAncestors(leaf, byId);
            if (chainUp.size() < 2) {
                continue;
            }
            for (int i = 1; i < chainUp.size(); i++) {
                RpkiCert anc = chainUp.get(i);
                if (Boolean.TRUE.equals(anc.getRevoked()) && !Boolean.TRUE.equals(leaf.getRevoked())) {
                    out.add(build(leaf, anc, "CHAIN_REVOKED_ANCESTOR_ACTIVE", "CRITICAL",
                            String.format("祖先证书 id=%d 已标记撤销，但后代 id=%d 仍当前有效且未撤销",
                                    anc.getId(), leaf.getId()),
                            "RFC6487"));
                }
            }
        }
    }

    private List<RpkiCert> walkAncestors(RpkiCert leaf, Map<Long, RpkiCert> byId) {
        List<RpkiCert> chain = new ArrayList<>();
        RpkiCert cur = leaf;
        Set<Long> seen = new HashSet<>();
        while (cur != null && cur.getId() != null && seen.add(cur.getId())) {
            chain.add(cur);
            Long pid = cur.getParentCertId();
            cur = pid == null ? null : byId.get(pid);
        }
        return chain;
    }

    /**
     * 使用 IPv4 首字节桶减少无关成对比较；全前缀 0.0.0.0/0 进入全局桶。
     *
     * @return 实际枚举的证书对数量（去重后）
     */
    private int detectPairwiseIndexed(List<RpkiCert> valid, Map<Long, RpkiCert> byId, List<ConflictResult> out) {
        Map<Integer, Set<Long>> bucket = new HashMap<>();
        Set<Long> universal = new HashSet<>();
        Map<Long, RpkiCert> idToCert = new HashMap<>();

        for (RpkiCert c : valid) {
            if (c.getId() == null) {
                continue;
            }
            idToCert.put(c.getId(), c);
            List<String> v4 = normalizeIpv4Cidrs(parseJsonList(c.getIpv4Prefixes()));
            boolean any = false;
            for (String p : v4) {
                any = true;
                if ("0.0.0.0/0".equals(p)) {
                    universal.add(c.getId());
                } else {
                    int oct = firstOctetOfCidr(p);
                    bucket.computeIfAbsent(oct, k -> new LinkedHashSet<>()).add(c.getId());
                }
            }
            if (!any) {
                bucket.computeIfAbsent(-1, k -> new LinkedHashSet<>()).add(c.getId());
            }
        }

        Set<String> seenPairs = new HashSet<>();
        int pairs = 0;
        for (RpkiCert a : valid) {
            if (a.getId() == null) {
                continue;
            }
            Set<Long> cand = new LinkedHashSet<>();
            cand.addAll(universal);
            List<String> v4a = normalizeIpv4Cidrs(parseJsonList(a.getIpv4Prefixes()));
            if (v4a.isEmpty()) {
                cand.addAll(bucket.getOrDefault(-1, Collections.emptySet()));
            } else {
                for (String p : v4a) {
                    if ("0.0.0.0/0".equals(p)) {
                        cand.addAll(idToCert.keySet());
                    } else {
                        cand.addAll(bucket.getOrDefault(firstOctetOfCidr(p), Collections.emptySet()));
                    }
                }
            }
            cand.remove(a.getId());
            for (Long bid : cand) {
                RpkiCert b = idToCert.get(bid);
                if (b == null) {
                    continue;
                }
                long i1 = Math.min(a.getId(), b.getId());
                long i2 = Math.max(a.getId(), b.getId());
                String pkey = i1 + ":" + i2;
                if (!seenPairs.add(pkey)) {
                    continue;
                }
                pairs++;
                if (!hasValidityOverlap(a, b)) {
                    continue;
                }
                detectIpPair(a, b, out);
                detectAsPair(a, b, out);
                detectIpAsBindingHeuristic(a, b, out);
                detectDirectRevokedPair(a, b, out);
            }
        }
        return pairs;
    }

    private static int firstOctetOfCidr(String cidr) {
        try {
            String ip = cidr.split("/")[0];
            return Integer.parseInt(ip.split("\\.")[0]) & 0xff;
        } catch (Exception e) {
            return -1;
        }
    }

    private void detectIpPair(RpkiCert a, RpkiCert b, List<ConflictResult> out) {
        List<String> aV4 = normalizeIpv4Cidrs(parseJsonList(a.getIpv4Prefixes()));
        List<String> bV4 = normalizeIpv4Cidrs(parseJsonList(b.getIpv4Prefixes()));
        if (!aV4.isEmpty() && !bV4.isEmpty()) {
            boolean apb = isResourceParentForScope(a, b);
            boolean bpa = isResourceParentForScope(b, a);
            if (apb) {
                List<String> ex = collectOutOfScopePrefixes(aV4, bV4);
                if (!ex.isEmpty()) {
                    out.add(build(b, a, "IP_OUT_OF_SCOPE", "CRITICAL",
                            "子证书 IP 超出父证书授权: " + ex.stream().limit(5).collect(Collectors.joining(", ")),
                            "RFC3779-2.2"));
                }
            } else if (bpa) {
                List<String> ex = collectOutOfScopePrefixes(bV4, aV4);
                if (!ex.isEmpty()) {
                    out.add(build(a, b, "IP_OUT_OF_SCOPE", "CRITICAL",
                            "子证书 IP 超出父证书授权: " + ex.stream().limit(5).collect(Collectors.joining(", ")),
                            "RFC3779-2.2"));
                }
            } else if (!restrictPeerConflicts || isComparablePeerContext(a, b)) {
                for (String p1 : aV4) {
                    for (String p2 : bV4) {
                        if (IPAddressUtils.isIPv4CidrContains(p1, p2) || IPAddressUtils.isIPv4CidrContains(p2, p1)) {
                            out.add(build(a, b, "IP_CONTAINMENT_PEER", "HIGH",
                                    "IPv4 前缀包含（同级/同签发上下文）: " + p1 + " vs " + p2, "RFC6811"));
                        } else if (IPAddressUtils.isIPv4CidrOverlapping(p1, p2)) {
                            out.add(build(a, b, "IP_OVERLAP_PEER", "HIGH",
                                    "IPv4 前缀重叠（同级/同签发上下文）: " + p1 + " vs " + p2, "RFC6811"));
                        } else if (IPAddressUtils.areAdjacentIpv4SamePrefixLen(p1, p2)) {
                            out.add(build(a, b, "IP_ADJACENT_BORDER", "MEDIUM",
                                    "IPv4 同长相邻前缀（同级/同签发上下文）: " + p1 + " | " + p2, "RFC6811"));
                        }
                    }
                }
            }
        }

        List<String> a6 = Ipv6CidrUtils.normalizeIpv6Cidrs(parseJsonList(a.getIpv6Prefixes()));
        List<String> b6 = Ipv6CidrUtils.normalizeIpv6Cidrs(parseJsonList(b.getIpv6Prefixes()));
        if (!a6.isEmpty() && !b6.isEmpty()) {
            boolean apb = isResourceParentForScope(a, b);
            boolean bpa = isResourceParentForScope(b, a);
            if (apb) {
                List<String> ex = collectOutOfScopeIpv6(a6, b6);
                if (!ex.isEmpty()) {
                    out.add(build(b, a, "IP6_OUT_OF_SCOPE", "CRITICAL",
                            "子证书 IPv6 超出父证书授权: " + String.join(", ", ex.subList(0, Math.min(4, ex.size()))),
                            "RFC3779-2.2"));
                }
            } else if (bpa) {
                List<String> ex = collectOutOfScopeIpv6(b6, a6);
                if (!ex.isEmpty()) {
                    out.add(build(a, b, "IP6_OUT_OF_SCOPE", "CRITICAL",
                            "子证书 IPv6 超出父证书授权: " + String.join(", ", ex.subList(0, Math.min(4, ex.size()))),
                            "RFC3779-2.2"));
                }
            } else if (!restrictPeerConflicts || isComparablePeerContext(a, b)) {
                for (String p1 : a6) {
                    for (String p2 : b6) {
                        if (Ipv6CidrUtils.isIpv6CidrContains(p1, p2) || Ipv6CidrUtils.isIpv6CidrContains(p2, p1)) {
                            out.add(build(a, b, "IP6_CONTAINMENT_PEER", "HIGH",
                                    "IPv6 前缀包含（同级/同签发上下文）: " + p1 + " vs " + p2, "RFC6811"));
                        } else if (Ipv6CidrUtils.isIpv6CidrOverlapping(p1, p2)) {
                            out.add(build(a, b, "IP6_OVERLAP_PEER", "HIGH",
                                    "IPv6 前缀重叠（同级/同签发上下文）: " + p1 + " vs " + p2, "RFC6811"));
                        } else if (Ipv6CidrUtils.areAdjacentSamePrefixLen(p1, p2)) {
                            out.add(build(a, b, "IP6_ADJACENT_BORDER", "MEDIUM",
                                    "IPv6 同长相邻前缀（同级/同签发上下文）: " + p1 + " | " + p2, "RFC6811"));
                        }
                    }
                }
            }
        }
    }

    private void detectAsPair(RpkiCert a, RpkiCert b, List<ConflictResult> out) {
        Set<String> aAs = new HashSet<>(normalizeAsNumbers(parseJsonList(a.getAsNumbers())));
        Set<String> bAs = new HashSet<>(normalizeAsNumbers(parseJsonList(b.getAsNumbers())));
        if (aAs.isEmpty() || bAs.isEmpty()) {
            return;
        }
        if (isResourceParentForScope(a, b) && !aAs.containsAll(bAs)) {
            out.add(build(b, a, "AS_OUT_OF_SCOPE", "CRITICAL", "子证书 AS 超出父证书授权", "RFC3779-3.2"));
        } else if (isResourceParentForScope(b, a) && !bAs.containsAll(aAs)) {
            out.add(build(a, b, "AS_OUT_OF_SCOPE", "CRITICAL", "子证书 AS 超出父证书授权", "RFC3779-3.2"));
        } else if (!isResourceParentForScope(a, b) && !isResourceParentForScope(b, a)) {
            if (restrictPeerConflicts && !isComparablePeerContext(a, b)) {
                return;
            }
            Set<String> inter = new LinkedHashSet<>(aAs);
            inter.retainAll(bAs);
            if (!inter.isEmpty()) {
                out.add(build(a, b, "AS_OVERLAP_PEER", "HIGH",
                        "对等证书 AS 授权交集（同级/同签发上下文，潜在重复授权）: "
                                + inter.stream().limit(12).collect(Collectors.joining(", ")),
                        "RFC6811"));
            }
        }
    }

    /**
     * 证书不存 ROA；若两证在同一时间窗内 IPv4 重叠且双方 AS 集合均非空且交集为空，记为「IP-AS 绑定」类启发式告警。
     */
    private void detectIpAsBindingHeuristic(RpkiCert a, RpkiCert b, List<ConflictResult> out) {
        List<String> aV4 = normalizeIpv4Cidrs(parseJsonList(a.getIpv4Prefixes()));
        List<String> bV4 = normalizeIpv4Cidrs(parseJsonList(b.getIpv4Prefixes()));
        Set<String> aAs = new HashSet<>(normalizeAsNumbers(parseJsonList(a.getAsNumbers())));
        Set<String> bAs = new HashSet<>(normalizeAsNumbers(parseJsonList(b.getAsNumbers())));
        if (aV4.isEmpty() || bV4.isEmpty() || aAs.isEmpty() || bAs.isEmpty()) {
            return;
        }
        if (restrictPeerConflicts && !isComparablePeerContext(a, b)) {
            return;
        }
        boolean ipTouch = false;
        String sample = "";
        outer:
        for (String p1 : aV4) {
            for (String p2 : bV4) {
                if (IPAddressUtils.isIPv4CidrOverlapping(p1, p2)
                        || IPAddressUtils.isIPv4CidrContains(p1, p2)
                        || IPAddressUtils.isIPv4CidrContains(p2, p1)) {
                    ipTouch = true;
                    sample = p1 + " vs " + p2;
                    break outer;
                }
            }
        }
        if (!ipTouch) {
            return;
        }
        Set<String> inter = new LinkedHashSet<>(aAs);
        inter.retainAll(bAs);
        if (inter.isEmpty()) {
            out.add(build(a, b, "IP_AS_BINDING_HEURISTIC", "MEDIUM",
                    "IPv4 资源重叠但 AS 集合不相交（非 ROA 严格判定，仅供参考）: " + sample
                            + " | AS_A=" + aAs.stream().limit(6).collect(Collectors.joining(","))
                            + " AS_B=" + bAs.stream().limit(6).collect(Collectors.joining(",")),
                    "RFC6811-heuristic"));
        }
    }

    private void detectDirectRevokedPair(RpkiCert a, RpkiCert b, List<ConflictResult> out) {
        if (isResourceParentForScope(b, a) && Boolean.TRUE.equals(b.getRevoked()) && !Boolean.TRUE.equals(a.getRevoked())) {
            out.add(build(a, b, "CHAIN_RESIDUAL_AFTER_REVOKE", "CRITICAL",
                    "父已撤销子仍有效（直接父子）", "RFC6487-4.8"));
        }
        if (isResourceParentForScope(a, b) && Boolean.TRUE.equals(a.getRevoked()) && !Boolean.TRUE.equals(b.getRevoked())) {
            out.add(build(b, a, "CHAIN_RESIDUAL_AFTER_REVOKE", "CRITICAL",
                    "父已撤销子仍有效（直接父子）", "RFC6487-4.8"));
        }
    }

    private static boolean hasValidityOverlap(RpkiCert a, RpkiCert b) {
        if (a.getNotBefore() == null || a.getNotAfter() == null || b.getNotBefore() == null || b.getNotAfter() == null) {
            return true;
        }
        return !a.getNotAfter().isBefore(b.getNotBefore()) && !b.getNotAfter().isBefore(a.getNotBefore());
    }

    /**
     * 用于「子资源 ⊆ 父资源」「父撤子活」等强语义：默认仅信任 {@code parent_cert_id}，避免 DN 字符串误配。
     */
    private boolean isResourceParentForScope(RpkiCert parent, RpkiCert child) {
        if (useDnFallbackForResourceParent) {
            return isParentChildDnOrId(parent, child);
        }
        if (parent == null || child == null || parent.getId() == null || child.getParentCertId() == null) {
            return false;
        }
        if (!child.getParentCertId().equals(parent.getId())) {
            return false;
        }
        if (requireAkiSkiForDelegatedScope) {
            String aki = child.getAuthorityKeyId();
            String ski = parent.getSubjectKeyId();
            if (aki != null && !aki.isBlank() && ski != null && !ski.isBlank()
                    && !aki.equalsIgnoreCase(ski)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 对等资源冲突可比：同一 RIR，且（同一签发者 DN，或同一显式父 id＝同为某一父下的兄弟）。
     */
    private static boolean isComparablePeerContext(RpkiCert a, RpkiCert b) {
        if (!sameNonBlank(a.getRir(), b.getRir())) {
            return false;
        }
        if (sameIssuerDnNormalized(a, b)) {
            return true;
        }
        Long pa = a.getParentCertId();
        Long pb = b.getParentCertId();
        return pa != null && pa.equals(pb);
    }

    private static boolean sameIssuerDnNormalized(RpkiCert a, RpkiCert b) {
        String ia = normalizeDnLike(a.getIssuer());
        String ib = normalizeDnLike(b.getIssuer());
        return ia != null && ib != null && !ia.isEmpty() && ia.equalsIgnoreCase(ib);
    }

    private static String normalizeDnLike(String dn) {
        if (dn == null) {
            return null;
        }
        return dn.trim().replaceAll("\\s+", " ");
    }

    /** issuer/subject 与 parent_cert_id 任一成立即视为父子（旧逻辑，易产生误报）。 */
    private static boolean isParentChildDnOrId(RpkiCert parent, RpkiCert child) {
        if (parent == null || child == null) {
            return false;
        }
        if (child.getParentCertId() != null && parent.getId() != null && child.getParentCertId().equals(parent.getId())) {
            return true;
        }
        return sameNonBlank(parent.getRir(), child.getRir())
                && sameNonBlank(parent.getSubject(), child.getIssuer());
    }

    private static boolean sameNonBlank(String x, String y) {
        if (x == null || y == null) {
            return false;
        }
        String xx = x.trim();
        String yy = y.trim();
        return !xx.isEmpty() && xx.equalsIgnoreCase(yy);
    }

    private static List<String> collectOutOfScopeIpv6(List<String> parentV6, List<String> childV6) {
        List<String> exceeded = new ArrayList<>();
        for (String ch : childV6) {
            boolean ok = false;
            for (String pa : parentV6) {
                if (Ipv6CidrUtils.isIpv6CidrContains(pa, ch)) {
                    ok = true;
                    break;
                }
            }
            if (!ok) {
                exceeded.add(ch);
            }
        }
        return exceeded;
    }

    private static List<String> normalizeIpv4Cidrs(List<String> values) {
        List<String> out = new ArrayList<>();
        if (values == null) {
            return out;
        }
        for (String v : values) {
            if (v == null) {
                continue;
            }
            String s = v.trim();
            if (s.isEmpty() || s.startsWith("RFC3779_")) {
                continue;
            }
            if (s.contains("/") && !s.contains("-")) {
                out.add(s);
            }
        }
        return out;
    }

    private static List<String> normalizeAsNumbers(List<String> values) {
        List<String> out = new ArrayList<>();
        if (values == null) {
            return out;
        }
        for (String v : values) {
            if (v == null) {
                continue;
            }
            String s = v.trim();
            if (s.isEmpty() || s.startsWith("RFC3779_")) {
                continue;
            }
            int dash = s.indexOf('-');
            if (dash > 0 && dash < s.length() - 1) {
                String a = s.substring(0, dash);
                String b = s.substring(dash + 1);
                if (a.matches("\\d+") && b.matches("\\d+")) {
                    BigInteger ba = new BigInteger(a);
                    BigInteger bb = new BigInteger(b);
                    if (ba.compareTo(bb) > 0) {
                        continue;
                    }
                    if (bb.subtract(ba).compareTo(BigInteger.valueOf(50_000)) <= 0) {
                        BigInteger cur = ba;
                        while (cur.compareTo(bb) <= 0) {
                            out.add(cur.toString());
                            cur = cur.add(BigInteger.ONE);
                        }
                    } else {
                        out.add(s);
                    }
                    continue;
                }
            }
            if (s.matches("AS\\d+")) {
                out.add(s.substring(2));
            } else if (s.matches("\\d+")) {
                out.add(s);
            }
        }
        return out;
    }

    private static List<String> collectOutOfScopePrefixes(List<String> parentV4, List<String> childV4) {
        List<String> exceeded = new ArrayList<>();
        for (String child : childV4) {
            boolean covered = false;
            for (String parent : parentV4) {
                if (IPAddressUtils.isIPv4CidrContains(parent, child)) {
                    covered = true;
                    break;
                }
            }
            if (!covered) {
                exceeded.add(child);
            }
        }
        return exceeded;
    }

    private List<String> parseJsonList(String jsonStr) {
        try {
            if (jsonStr == null || jsonStr.isEmpty()) {
                return new ArrayList<>();
            }
            List<String> list = GSON.fromJson(jsonStr, LIST_STRING);
            return list == null ? new ArrayList<>() : list;
        } catch (Exception e) {
            log.warn("解析 JSON 列表失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private ConflictResult build(RpkiCert a, RpkiCert b, String type, String severity, String details, String ref) {
        return ConflictResult.builder()
                .certIdA(a.getId())
                .certIdB(b.getId())
                .conflictType(type)
                .severity(severity)
                .ruleVersion(RULE_VERSION)
                .details(details)
                .ruleReference(ref)
                .build();
    }

    private void logStats(List<ConflictResult> deduped, long t0, int validCount, int pairCount) {
        Map<String, Long> byType = deduped.stream()
                .collect(Collectors.groupingBy(ConflictResult::getConflictType, Collectors.counting()));
        log.info("[RPKI-冲突] 统计: 有效证书={} 枚举对={} 去重冲突={} 耗时={}ms 按类型={}",
                validCount, pairCount, deduped.size(), System.currentTimeMillis() - t0, byType);
    }

    private List<ConflictResult> dedup(List<ConflictResult> list) {
        Map<String, ConflictResult> map = new LinkedHashMap<>();
        for (ConflictResult item : list) {
            String key = ConflictKeyBuilder.toLedgerKey(item);
            map.putIfAbsent(key, item);
        }
        return new ArrayList<>(map.values());
    }

    private void saveConflictRecords(List<ConflictResult> results) {
        int idx = 0;
        for (ConflictResult result : results) {
            idx++;
            if (results.size() > 300 && (idx % 300 == 0)) {
                log.info("冲突记录入库进度 {}/{}", idx, results.size());
            }
            String key = ConflictKeyBuilder.toLedgerKey(result);
            Long count = conflictRecordMapper.selectCount(new LambdaQueryWrapper<ConflictRecord>().eq(ConflictRecord::getConflictKey, key));
            if (count != null && count > 0) {
                continue;
            }
            ConflictRecord record = ConflictRecord.builder()
                    .certIdA(result.getCertIdA())
                    .certIdB(result.getCertIdB())
                    .conflictType(result.getConflictType())
                    .severity(result.getSeverity())
                    .ruleVersion(result.getRuleVersion())
                    .ruleReference(result.getRuleReference())
                    .conflictKey(key)
                    .details(result.getDetails())
                    .detectedAt(LocalDateTime.now(ZoneOffset.UTC))
                    .build();
            conflictRecordMapper.insert(record);
        }
    }

    private void updateCertConflictSummary(List<ConflictResult> results) {
        Map<Long, List<ConflictResult>> grouped = results.stream()
                .flatMap(r -> List.of(Map.entry(r.getCertIdA(), r), Map.entry(r.getCertIdB(), r)).stream())
                .collect(Collectors.groupingBy(Map.Entry::getKey, Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
        int u = 0;
        for (Map.Entry<Long, List<ConflictResult>> entry : grouped.entrySet()) {
            u++;
            if (grouped.size() > 300 && u % 300 == 0) {
                log.info("更新证书冲突摘要进度 {}/{}", u, grouped.size());
            }
            RpkiCert cert = rpkiCertMapper.selectById(entry.getKey());
            if (cert == null) {
                continue;
            }
            cert.setHasConflict(true);
            cert.setConflictDetails(entry.getValue().stream().map(ConflictResult::getDetails).distinct().collect(Collectors.joining("; ")));
            rpkiCertMapper.updateById(cert);
        }
    }

    private void resetConflictFlags(List<RpkiCert> certs) {
        int idx = 0;
        for (RpkiCert cert : certs) {
            idx++;
            if (certs.size() > 500 && idx % 400 == 0) {
                log.info("重置历史冲突标记进度 {}/{}", idx, certs.size());
            }
            cert.setHasConflict(false);
            cert.setConflictDetails(null);
            cert.setFabricTxId(null);
            cert.setFabricBlockNum(null);
            cert.setIsSentToFabric(false);
            cert.setFabricSendTime(null);
            rpkiCertMapper.updateById(cert);
        }
    }
}
