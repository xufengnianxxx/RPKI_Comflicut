package rpkiccjava;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import rpkiccjava.dto.DetectBatchRequest;
import rpkiccjava.model.CertificateAsset;
import rpkiccjava.model.ConflictEvidenceAsset;
import rpkiccjava.model.ConflictType;
import org.hyperledger.fabric.contract.Context;
import org.hyperledger.fabric.contract.ContractInterface;
import org.hyperledger.fabric.contract.annotation.Contract;
import org.hyperledger.fabric.contract.annotation.Default;
import org.hyperledger.fabric.contract.annotation.Info;
import org.hyperledger.fabric.contract.annotation.Transaction;
import org.hyperledger.fabric.shim.ChaincodeException;
import org.hyperledger.fabric.shim.ledger.KeyModification;
import org.hyperledger.fabric.shim.ledger.KeyValue;
import org.hyperledger.fabric.shim.ledger.QueryResultsIterator;

import java.lang.reflect.Type;
import java.math.BigInteger;
import java.time.Instant;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * RPKI 冲突校验智能合约（开题：<b>链上核心校验 + 链下辅助计算</b>，冲突规则合约化编码）。
 * <p><b>代码即法律</b>：判定仅依赖整数 / {@link BigInteger} / 枚举序；禁止浮点、随机、非确定性时间参与分支
 * （{@code timestamp}/{@code detectedAt} 仅写入状态，不参与 if/else 条件）。</p>
 * <p><b>背书确定性</b>：{@code detectedAt}、证书 {@code timestamp} 缺省时一律使用
 * {@link org.hyperledger.fabric.shim.ChaincodeStub#getTxTimestamp()} 转毫秒，禁止
 * {@code System.currentTimeMillis()}，否则多 peer 模拟时刻不同会导致提案读写集不一致、交易无法提交。</p>
 *
 * <h2>世界状态键</h2>
 * <ul>
 *   <li>{@code CERT|&lt;certHashLower&gt;} → {@link CertificateAsset}</li>
 *   <li>{@code CONFLICT|&lt;id&gt;} → {@link ConflictEvidenceAsset}</li>
 * </ul>
 *
 * <h2>形式化规则、<b>执行顺序</b>与严重度</h2>
 * <p><b>执行顺序</b>（{@link #runPairDetection}）：① 继承越权；② AS 冲突（需共享 IP 资源）；③ IPv4/IPv6 前缀（每对 if/else：包含 → 部分重叠 → 异长相邻）。</p>
 * <p><b>主类型</b>：{@link ConflictType}{@code #ordinal()} 最大者；序满足 继承 &gt; AS &gt; 前缀（包含 &gt; 重叠 &gt; 相邻）&gt; MISSING。</p>
 *
 * <h2>存证路径</h2>
 * 命中冲突时通过 {@link #writeConflictEvidenceBatch(Context, List)} 落库，与对外交易 {@link #recordConflictEvidenceBatch} 共用同一写入逻辑。
 */
@Contract(
        name = "RpkiConflictContract",
        info = @Info(
                title = "rpkiccjava RPKI Conflict",
                version = "3.2.2",
                description = "合约化冲突规则：前缀包含/部分重叠/异长相邻；AS 不一致；继承越权"))
@Default
public class RpkiccjavaContract implements ContractInterface {

    private static final Logger LOG = Logger.getLogger(RpkiccjavaContract.class.getName());
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Type LIST_CERT = new TypeToken<List<CertificateAsset>>() {}.getType();
    private static final Type LIST_EVIDENCE = new TypeToken<List<ConflictEvidenceAsset>>() {}.getType();

    private static String certKey(String hash) {
        return "CERT|" + hash.toLowerCase(Locale.ROOT).trim();
    }

    private static String conflictStateKey(String key) {
        return "CONFLICT|" + key;
    }

    @Transaction(intent = Transaction.TYPE.SUBMIT)
    public String storeCertificateBatch(final Context ctx, final String batchJson) {
        List<CertificateAsset> batch = GSON.fromJson(batchJson, LIST_CERT);
        if (batch == null || batch.isEmpty()) {
            throw new ChaincodeException("empty batch");
        }
        long now = txTimestampEpochMs(ctx);
        List<String> written = new ArrayList<>();
        int n = 0;
        for (CertificateAsset a : batch) {
            if (a.getCertHash() == null || a.getCertHash().isBlank()) {
                continue;
            }
            if (a.getTimestamp() <= 0L) {
                a.setTimestamp(now);
            }
            byte[] data = GSON.toJson(a).getBytes(StandardCharsets.UTF_8);
            ctx.getStub().putState(certKey(a.getCertHash()), data);
            written.add(a.getCertHash().trim());
            n++;
        }
        log(ctx, "storeCertificateBatch stored=" + n);
        return certBatchResultJson(ctx, n, written);
    }

    /**
     * 批量记录冲突证据；与 detect 命中时调用的 {@link #writeConflictEvidenceBatch} 为同一路径，保证「代码即法律」写入一致。
     */
    @Transaction(intent = Transaction.TYPE.SUBMIT)
    public String recordConflictEvidenceBatch(final Context ctx, final String batchJson) {
        List<ConflictEvidenceAsset> batch = GSON.fromJson(batchJson, LIST_EVIDENCE);
        if (batch == null || batch.isEmpty()) {
            throw new ChaincodeException("empty conflict batch");
        }
        List<String> keys = writeConflictEvidenceBatch(ctx, batch);
        log(ctx, "recordConflictEvidenceBatch stored=" + keys.size());
        return evidenceBatchResultJson(ctx, keys.size(), keys);
    }

    @Transaction(intent = Transaction.TYPE.SUBMIT)
    public String recordConflictEvidence(final Context ctx, final String conflictJson) {
        ConflictEvidenceAsset rec = GSON.fromJson(conflictJson, ConflictEvidenceAsset.class);
        String id = stateConflictId(rec);
        if (rec == null || id == null || id.isBlank()) {
            throw new ChaincodeException("invalid conflict json: need conflictId or conflictKey");
        }
        List<String> keys = writeConflictEvidenceBatch(ctx, List.of(rec));
        return evidenceBatchResultJson(ctx, keys.size(), keys);
    }

    @Transaction(intent = Transaction.TYPE.SUBMIT)
    public String detectConflictOnChain(final Context ctx, final String certHashA, final String certHashB) {
        ConflictDetectResult out = new ConflictDetectResult();
        out.txId = ctx.getStub().getTxId();
        List<String> logLines = new ArrayList<>();

        CertificateAsset a = readCert(ctx, certHashA);
        CertificateAsset b = readCert(ctx, certHashB);
        if (a == null || b == null) {
            String msg = "MISSING_ASSET aNull=" + (a == null) + " bNull=" + (b == null);
            logLines.add(msg);
            LOG.info("[tx=" + out.txId + "] detect pair MISSING_ASSET " + msg);
            fillMissing(out, logLines, certHashA, certHashB);
            return GSON.toJson(out);
        }

        logLines.add("BEGIN detect certHashA=" + certHashA + " certHashB=" + certHashB);
        LOG.info("[tx=" + out.txId + "] BEGIN detect pair " + certHashA + " vs " + certHashB);

        PairDetectResult pr = runPairDetection(ctx, null, a, b, logLines);
        if (pr.evidence != null) {
            List<String> keys = writeConflictEvidenceBatch(ctx, List.of(pr.evidence));
            if (!keys.isEmpty()) {
                pr.persistedEvidenceKey = keys.get(0);
                logLines.add("EVIDENCE_VIA_BATCH_WRITER key=" + pr.persistedEvidenceKey);
            }
        }
        applyPairToResult(out, pr, certHashA, certHashB, logLines);
        out.pairsExamined = 1;
        out.conflictingPairs = ConflictType.NONE.equals(pr.primaryType) ? 0 : 1;
        out.involvedCertHashes = new ArrayList<>(List.of(orderHash(certHashA, certHashB)[0], orderHash(certHashA, certHashB)[1]));

        logLines.add("VERDICT=" + out.verdict + " primary=" + out.primaryConflictType);
        log(ctx, "detect done verdict=" + out.verdict + " primary=" + out.primaryConflictType);
        LOG.info("[tx=" + out.txId + "] END detect verdict=" + out.verdict + " primary=" + out.primaryConflictType);
        return GSON.toJson(out);
    }

    @Transaction(intent = Transaction.TYPE.SUBMIT)
    public String detectConflictOnChainBatch(final Context ctx, final String requestJson) {
        DetectBatchRequest req = GSON.fromJson(requestJson, DetectBatchRequest.class);
        if (req == null) {
            throw new ChaincodeException("null batch request");
        }
        String mode = req.getMode() == null ? "STATE_HASHES" : req.getMode().trim().toUpperCase(Locale.ROOT);
        ConflictDetectResult out = new ConflictDetectResult();
        out.txId = ctx.getStub().getTxId();
        List<String> logLines = new ArrayList<>();
        List<ConflictEvidenceAsset> evidenceToWrite = new ArrayList<>();
        List<FindingEntry> allStructured = new ArrayList<>();
        List<String> allRaw = new ArrayList<>();
        Set<String> involved = new LinkedHashSet<>();
        int pairs = 0;
        int badPairs = 0;

        if ("INLINE_ASSETS".equals(mode)) {
            List<CertificateAsset> certs = req.getCertificates();
            if (certs == null || certs.size() < 2) {
                throw new ChaincodeException("INLINE_ASSETS needs at least 2 certificates");
            }
            Map<String, CertificateAsset> byLower = new LinkedHashMap<>();
            for (CertificateAsset c : certs) {
                if (c != null && c.getCertHash() != null && !c.getCertHash().isBlank()) {
                    byLower.put(c.getCertHash().trim().toLowerCase(Locale.ROOT), c);
                }
            }
            TreeSet<String> sorted = new TreeSet<>(byLower.keySet());
            List<String> list = new ArrayList<>(sorted);
            LOG.info("[tx=" + out.txId + "] BATCH INLINE nCerts=" + list.size());
            for (int i = 0; i < list.size(); i++) {
                for (int j = i + 1; j < list.size(); j++) {
                    CertificateAsset ca = byLower.get(list.get(i));
                    CertificateAsset cb = byLower.get(list.get(j));
                    pairs++;
                    PairDetectResult pr = runPairDetection(ctx, byLower, ca, cb, logLines);
                    if (!ConflictType.NONE.equals(pr.primaryType)) {
                        badPairs++;
                    }
                    if (pr.evidence != null) {
                        evidenceToWrite.add(pr.evidence);
                    }
                    mergePair(pr, allRaw, allStructured, involved, ca.getCertHash(), cb.getCertHash());
                }
            }
        } else {
            List<String> hashes = req.getCertHashes();
            if (hashes == null || hashes.size() < 2) {
                throw new ChaincodeException("STATE_HASHES needs at least 2 certHashes");
            }
            TreeSet<String> sorted = new TreeSet<>();
            for (String h : hashes) {
                if (h != null && !h.isBlank()) {
                    sorted.add(h.trim());
                }
            }
            List<String> list = new ArrayList<>(sorted);
            LOG.info("[tx=" + out.txId + "] BATCH STATE_HASHES n=" + list.size());
            for (int i = 0; i < list.size(); i++) {
                for (int j = i + 1; j < list.size(); j++) {
                    String ha = list.get(i);
                    String hb = list.get(j);
                    pairs++;
                    CertificateAsset a = readCert(ctx, ha);
                    CertificateAsset b = readCert(ctx, hb);
                    if (a == null || b == null) {
                        String msg = "MISSING_ASSET pair ha=" + ha + " hb=" + hb + " aNull=" + (a == null) + " bNull=" + (b == null);
                        logLines.add(msg);
                        LOG.info("[tx=" + out.txId + "] " + msg);
                        allRaw.add(msg);
                        FindingEntry fe = new FindingEntry();
                        fe.type = ConflictType.MISSING_ASSET.name();
                        fe.detail = msg;
                        fe.certHashA = ha;
                        fe.certHashB = hb;
                        fe.conflictScope = inferConflictScope(msg);
                        fe.involvedCertHashChild = null;
                        fe.rulePhase = inferRulePhase(msg);
                        allStructured.add(fe);
                        involved.add(ha.trim());
                        involved.add(hb.trim());
                        badPairs++;
                        continue;
                    }
                    PairDetectResult pr = runPairDetection(ctx, null, a, b, logLines);
                    if (!ConflictType.NONE.equals(pr.primaryType)) {
                        badPairs++;
                    }
                    if (pr.evidence != null) {
                        evidenceToWrite.add(pr.evidence);
                    }
                    mergePair(pr, allRaw, allStructured, involved, ha, hb);
                }
            }
        }

        if (!evidenceToWrite.isEmpty()) {
            List<String> keys = writeConflictEvidenceBatch(ctx, evidenceToWrite);
            logLines.add("EVIDENCE_BATCH_WRITER count=" + keys.size());
            out.persistedEvidenceKeys = keys;
            LOG.info("[tx=" + out.txId + "] batch writer persisted keys=" + keys.size());
        } else {
            out.persistedEvidenceKeys = List.of();
        }

        out.findings = allStructured;
        out.rawFindings = allRaw;
        out.log = logLines;
        out.pairsExamined = pairs;
        out.conflictingPairs = badPairs;
        out.involvedCertHashes = new ArrayList<>(involved);

        ConflictType worst = ConflictType.NONE;
        for (FindingEntry fe : allStructured) {
            worst = maxSeverity(worst, fe.type);
        }
        out.primaryConflictType = worst.name();
        out.legacyWorstCode = worst.equals(ConflictType.NONE) ? "NONE" : allRaw.isEmpty() ? "NONE" : legacyCodeFromRaw(allRaw);
        out.verdict = worst.equals(ConflictType.NONE) ? "NO_CONFLICT" : "CONFLICT";

        log(ctx, "detectBatch verdict=" + out.verdict + " pairs=" + pairs + " bad=" + badPairs);
        LOG.info("[tx=" + out.txId + "] END batch verdict=" + out.verdict);
        return GSON.toJson(out);
    }

    // ————————————————————————————————————————————————————————————————
    // 与 recordConflictEvidenceBatch 共用的确定性写入（detect 命中时亦走此路径）
    // ————————————————————————————————————————————————————————————————

    private List<String> writeConflictEvidenceBatch(Context ctx, List<ConflictEvidenceAsset> batch) {
        List<String> keys = new ArrayList<>();
        if (batch == null) {
            return keys;
        }
        for (ConflictEvidenceAsset rec : batch) {
            String id = stateConflictId(rec);
            if (id == null || id.isBlank()) {
                continue;
            }
            normalizeEvidence(ctx, rec, id);
            byte[] data = GSON.toJson(rec).getBytes(StandardCharsets.UTF_8);
            ctx.getStub().putState(conflictStateKey(id), data);
            keys.add(id);
        }
        return keys;
    }

    // ————————————————————————————————————————————————————————————————
    // 前缀形式化谓词（IPv4 / IPv6 对称，可审计）
    // ————————————————————————————————————————————————————————————————

    /**
     * IPv4 <b>完全包含</b>（CIDR 区间语义，可计算、无歧义）：
     * <p>设前缀 P 对应地址闭区间 {@code [S(P), E(P)]}（32 位无符号整数轴，见 {@link Prefix4#blockStartU32}/{@link Prefix4#blockEndU32}）。
     * 「P 包含 Q」⇔ {@code plen(P) ≤ plen(Q)} 且 {@code [S(Q),E(Q)] ⊆ [S(P),E(P)]}
     * ⇔ {@code S(P) ≤ S(Q) ∧ E(Q) ≤ E(P)}（BigInteger 比较）。</p>
     * <p>本谓词：{@code contains(P,Q) ∨ contains(Q,P)}，即任一方块覆盖另一方整块。</p>
     */
    private static boolean isPrefixContained(Prefix4 a, Prefix4 b) {
        return Prefix4.cidrIntervalSubset(a, b) || Prefix4.cidrIntervalSubset(b, a);
    }

    /**
     * IPv6：同上，区间为 128 位无符号，{@link Prefix6#blockStart}/{@link Prefix6#blockEnd}。
     */
    private static boolean isPrefixContained(Prefix6 a, Prefix6 b) {
        return Prefix6.cidrIntervalSubset(a, b) || Prefix6.cidrIntervalSubset(b, a);
    }

    /**
     * <b>部分重叠</b>（真交叉，非包含）：
     * <p>形式化：{@code intervalOverlap(a,b) ∧ ¬cidrIntervalSubset(a,b) ∧ ¬cidrIntervalSubset(b,a)}。</p>
     * <p>区间相交判定：{@code max(Sa,Sb) ≤ min(Ea,Eb)}（闭区间相交非空）。</p>
     */
    private static boolean isPrefixOverlappingPartial(Prefix4 a, Prefix4 b) {
        if (isPrefixContained(a, b)) {
            return false;
        }
        return Prefix4.cidrIntervalsIntersect(a, b);
    }

    private static boolean isPrefixOverlappingPartial(Prefix6 a, Prefix6 b) {
        if (isPrefixContained(a, b)) {
            return false;
        }
        return Prefix6.cidrIntervalsIntersect(a, b);
    }

    /**
     * <b>异长相邻路由歧义</b>（开题约束）：
     * <ol>
     *   <li>{@code plen(a) ≠ plen(b)}；</li>
     *   <li>互不包含且区间不相交；</li>
     *   <li>{@link Prefix4#rangesAdjacent}：{@code E(a)+1 = S(b)} 或对称（无符号边界 +1）。</li>
     * </ol>
     */
    private static boolean isAdjacentDifferentLengthConflict(Prefix4 a, Prefix4 b) {
        if (a.plen == b.plen) {
            return false;
        }
        if (isPrefixContained(a, b) || Prefix4.cidrIntervalsIntersect(a, b)) {
            return false;
        }
        return Prefix4.rangesAdjacent(a, b);
    }

    private static boolean isAdjacentDifferentLengthConflict(Prefix6 a, Prefix6 b) {
        if (a.plen == b.plen) {
            return false;
        }
        if (isPrefixContained(a, b) || Prefix6.cidrIntervalsIntersect(a, b)) {
            return false;
        }
        return Prefix6.rangesAdjacent(a, b);
    }

    // ————————————————————————————————————————————————————————————————
    // AS 形式化谓词（ASN 用 Long 规范表示，避免字符串大小写歧义）
    // ————————————————————————————————————————————————————————————————

    /**
     * 存在共享 IP 资源：任一 v4 或 v6 前缀对满足「重叠」或「包含」之一。
     */
    private static boolean hasSharedIpResource(List<Prefix4> la, List<Prefix4> lb, List<Prefix6> l6a, List<Prefix6> l6b) {
        for (Prefix4 pa : la) {
            for (Prefix4 pb : lb) {
                if (Prefix4.cidrIntervalsIntersect(pa, pb) || isPrefixContained(pa, pb)) {
                    return true;
                }
            }
        }
        for (Prefix6 pa : l6a) {
            for (Prefix6 pb : l6b) {
                if (Prefix6.cidrIntervalsIntersect(pa, pb) || isPrefixContained(pa, pb)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * <b>AS 授权冲突</b>（与链下可对齐的布尔谓词，显式写出交集）：
     * <p>前提：{@code Sa ≠ ∅} 且 {@code Sb ≠ ∅}。记 {@code I = Sa ∩ Sb}。</p>
     * <p>冲突当且仅当 {@code (I = ∅) ∨ (Sa ≠ Sb)}。在有限集合上：两集相等且非空 ⇒ {@code I = Sa = Sb} 非空且相等判定为假；</p>
     * <p>不相交或真子集关系均使上式为真。实现用 {@link TreeSet} 保留序与确定性 {@link Object#equals}。</p>
     */
    private static boolean isASConflict(Set<Long> asA, Set<Long> asB) {
        if (asA == null || asB == null || asA.isEmpty() || asB.isEmpty()) {
            return false;
        }
        TreeSet<Long> inter = new TreeSet<>(asA);
        inter.retainAll(asB);
        boolean emptyIntersection = inter.isEmpty();
        boolean setsDiffer = !asA.equals(asB);
        return emptyIntersection || setsDiffer;
    }

    // ————————————————————————————————————————————————————————————————
    // 继承：子资源 ⊆ 父授权（最小化授权 / 单调性）
    // ————————————————————————————————————————————————————————————————

    /**
     * 对单张<b>子证书</b>相对已解析的<b>父证书</b>做越权检查。
     * <ul>
     *   <li>IPv4/IPv6：每个子前缀须被父的<strong>某一</strong>前缀覆盖（{@link Prefix4#contains} / {@link Prefix6#contains} 父包子）。</li>
     *   <li>AS：若父子均声明 AS，则子 AS 集合须为父 AS 集合的<strong>子集</strong>（逐元素 ∈）。</li>
     * </ul>
     *
     * @param child  子证书（被检查方）
     * @param parent 父证书；null 表示无法解析父，返回空列表（不视为本函数内的冲突）
     * @return 越权事实描述行，顺序固定：先 IPv4，再 IPv6，再 AS，便于审计与链下 diff
     */
    private static List<String> collectInheritanceViolations(CertificateAsset child, CertificateAsset parent) {
        List<String> findings = new ArrayList<>();
        if (parent == null || child == null || child.getCertHash() == null) {
            return findings;
        }

        List<Prefix4> c4 = parseAllV4(child.getIpv4Prefixes());
        List<Prefix4> p4 = parseAllV4(parent.getIpv4Prefixes());
        if (!c4.isEmpty() && p4.isEmpty()) {
            findings.add("INHERIT_IPV4_OUT_OF_SCOPE reason=no_parent_ipv4 child=" + child.getCertHash()
                    + " parent=" + parent.getCertHash());
        } else {
            for (Prefix4 c : c4) {
                if (!coveredByAnyV4(c, p4)) {
                    findings.add("INHERIT_IPV4_OUT_OF_SCOPE child=" + child.getCertHash()
                            + " parent=" + parent.getCertHash()
                            + " childPrefix=" + c + " parentAllows=" + p4);
                }
            }
        }

        List<Prefix6> c6 = parseAllV6(child.getIpv6Prefixes());
        List<Prefix6> p6 = parseAllV6(parent.getIpv6Prefixes());
        if (!c6.isEmpty() && p6.isEmpty()) {
            findings.add("INHERIT_IPV6_OUT_OF_SCOPE reason=no_parent_ipv6 child=" + child.getCertHash()
                    + " parent=" + parent.getCertHash());
        } else {
            for (Prefix6 c : c6) {
                if (!coveredByAnyV6(c, p6)) {
                    findings.add("INHERIT_IPV6_OUT_OF_SCOPE child=" + child.getCertHash()
                            + " parent=" + parent.getCertHash()
                            + " childPrefix=" + c + " parentAllows=" + p6);
                }
            }
        }

        TreeSet<Long> cas = normalizeAsLongSet(child.getAsNumbers());
        TreeSet<Long> pas = normalizeAsLongSet(parent.getAsNumbers());
        if (!cas.isEmpty() && !pas.isEmpty()) {
            for (Long asn : cas) {
                if (!pas.contains(asn)) {
                    findings.add("INHERIT_AS_OUT_OF_SCOPE child=" + child.getCertHash()
                            + " parent=" + parent.getCertHash()
                            + " as=" + asn + " parentAs=" + pas);
                }
            }
        }
        return findings;
    }

    private static void appendInheritanceFindings(String txId, List<String> lines, List<String> raw, List<String> log) {
        for (String x : lines) {
            raw.add(x);
            log.add(x);
            LOG.info("[AUDIT][tx=" + txId + "][phase=INHERIT][rule=VIOLATION] " + x);
        }
    }

    private static ConflictType maxSeverity(ConflictType current, String typeName) {
        if (typeName == null) {
            return current;
        }
        try {
            ConflictType t = ConflictType.valueOf(typeName);
            return t.ordinal() > current.ordinal() ? t : current;
        } catch (Exception e) {
            return current;
        }
    }

    private static void mergePair(PairDetectResult pr, List<String> allRaw, List<FindingEntry> allStructured,
                                  Set<String> involved, String ha, String hb) {
        allRaw.addAll(pr.rawFindings);
        for (FindingEntry fe : pr.structuredFindings) {
            FindingEntry copy = new FindingEntry();
            copy.type = fe.type;
            copy.detail = fe.detail;
            copy.certHashA = ha;
            copy.certHashB = hb;
            copy.conflictScope = fe.conflictScope;
            copy.involvedCertHashChild = fe.involvedCertHashChild;
            copy.rulePhase = fe.rulePhase;
            allStructured.add(copy);
        }
        involved.add(ha.trim());
        involved.add(hb.trim());
    }

    private static String legacyCodeFromRaw(List<String> raw) {
        String best = "NONE";
        int ord = -1;
        for (String f : raw) {
            String code = legacyExtractCode(f);
            int idx = legacyOrderIndex(code);
            if (idx > ord) {
                ord = idx;
                best = code;
            }
        }
        return best;
    }

    private static int legacyOrderIndex(String code) {
        List<String> order = List.of(
                "NONE",
                "IPV4_ADJACENT_LEN", "IPV6_ADJACENT_LEN",
                "IPV4_OVERLAP", "IPV6_OVERLAP",
                "IPV4_CONTAINMENT", "IPV6_CONTAINMENT",
                "AS_DISAGREE_ON_SHARED_IP",
                "INHERIT_IPV4_OUT_OF_SCOPE", "INHERIT_IPV6_OUT_OF_SCOPE", "INHERIT_AS_OUT_OF_SCOPE",
                "MISSING_ASSET");
        int i = order.indexOf(code);
        return Math.max(i, 0);
    }

    private static String legacyExtractCode(String f) {
        if (f == null) {
            return "NONE";
        }
        List<String> order = List.of(
                "MISSING_ASSET",
                "INHERIT_AS_OUT_OF_SCOPE", "INHERIT_IPV6_OUT_OF_SCOPE", "INHERIT_IPV4_OUT_OF_SCOPE",
                "AS_DISAGREE_ON_SHARED_IP",
                "IPV6_CONTAINMENT", "IPV4_CONTAINMENT",
                "IPV6_OVERLAP", "IPV4_OVERLAP",
                "IPV6_ADJACENT_LEN", "IPV4_ADJACENT_LEN");
        for (String p : order) {
            if (f.startsWith(p)) {
                return p;
            }
        }
        int sp = f.indexOf(' ');
        return sp > 0 ? f.substring(0, sp) : f;
    }

    private void fillMissing(ConflictDetectResult out, List<String> logLines, String certHashA, String certHashB) {
        out.log = logLines;
        out.verdict = "CONFLICT";
        out.primaryConflictType = ConflictType.MISSING_ASSET.name();
        out.legacyWorstCode = "MISSING_ASSET";
        FindingEntry fe = new FindingEntry();
        fe.type = ConflictType.MISSING_ASSET.name();
        fe.detail = logLines.get(0);
        fe.certHashA = certHashA;
        fe.certHashB = certHashB;
        fe.conflictScope = inferConflictScope(logLines.get(0));
        fe.involvedCertHashChild = null;
        fe.rulePhase = "MISSING";
        out.findings = List.of(fe);
        out.rawFindings = List.of(logLines.get(0));
        out.pairsExamined = 1;
        out.conflictingPairs = 1;
        out.persistedEvidenceKeys = List.of();
        out.involvedCertHashes = List.of(certHashA, certHashB);
    }

    private void applyPairToResult(ConflictDetectResult out, PairDetectResult pr, String certHashA, String certHashB,
                                   List<String> logLines) {
        out.findings = pr.structuredFindings;
        out.rawFindings = pr.rawFindings;
        out.primaryConflictType = pr.primaryType.name();
        out.legacyWorstCode = pr.rawFindings.isEmpty() ? "NONE" : legacyCodeFromRaw(pr.rawFindings);
        out.verdict = ConflictType.NONE.equals(pr.primaryType) ? "NO_CONFLICT" : "CONFLICT";
        out.log = logLines;
        out.persistedEvidenceKeys = pr.persistedEvidenceKey == null ? List.of() : List.of(pr.persistedEvidenceKey);
        if (pr.persistedEvidenceKey != null) {
            out.persistedEvidenceKey = pr.persistedEvidenceKey;
        }
    }

    /**
     * 单对证书完整判定：<b>执行顺序</b>为 继承 → AS（共享 IP 前提）→ IPv4 → IPv6；{@code findings} 追加顺序与此一致，便于审计追溯。
     */
    private PairDetectResult runPairDetection(Context ctx, Map<String, CertificateAsset> inlineByHash,
                                              CertificateAsset a, CertificateAsset b, List<String> logLines) {
        PairDetectResult pr = new PairDetectResult();
        List<String> raw = pr.rawFindings;
        final String tx = ctx.getStub().getTxId();
        final String pairLabel = Objects.requireNonNullElse(a.getCertHash(), "?") + "|" + Objects.requireNonNullElse(b.getCertHash(), "?");

        List<Prefix4> listA = parseAllV4(a.getIpv4Prefixes());
        List<Prefix4> listB = parseAllV4(b.getIpv4Prefixes());
        List<Prefix6> list6A = parseAllV6(a.getIpv6Prefixes());
        List<Prefix6> list6B = parseAllV6(b.getIpv6Prefixes());

        LOG.info("[AUDIT][tx=" + tx + "][phase=INHERIT][pair=" + pairLabel + "] begin inheritance checks (child vs resolved parent)");
        runInheritancePhase(ctx, inlineByHash, a, b, raw, logLines);

        LOG.info("[AUDIT][tx=" + tx + "][phase=AS][pair=" + pairLabel + "] begin AS conflict (requires shared IP resource)");
        checkASConflictOnSharedResource(listA, listB, list6A, list6B, a.getAsNumbers(), b.getAsNumbers(), raw, logLines, tx);

        LOG.info("[AUDIT][tx=" + tx + "][phase=IPV4][pair=" + pairLabel + "] prefix rules order=CONTAINMENT then OVERLAP then ADJ_LEN");
        checkV4PrefixRules(listA, listB, raw, logLines, tx, pairLabel);

        LOG.info("[AUDIT][tx=" + tx + "][phase=IPV6][pair=" + pairLabel + "] prefix rules order=CONTAINMENT then OVERLAP then ADJ_LEN");
        checkV6PrefixRules(list6A, list6B, raw, logLines, tx, pairLabel);

        for (String line : raw) {
            FindingEntry fe = new FindingEntry();
            fe.type = conflictTypeOfFinding(line).name();
            fe.detail = line;
            fe.certHashA = a.getCertHash();
            fe.certHashB = b.getCertHash();
            fe.conflictScope = inferConflictScope(line);
            fe.involvedCertHashChild = extractChildCertHashIfInherit(line);
            fe.rulePhase = inferRulePhase(line);
            pr.structuredFindings.add(fe);
        }

        pr.primaryType = worstConflictType(pr.structuredFindings);

        LOG.info("[AUDIT][tx=" + tx + "][phase=SUMMARY][pair=" + pairLabel + "] primaryType=" + pr.primaryType
                + " rawFindingsCount=" + raw.size());

        if (!ConflictType.NONE.equals(pr.primaryType)) {
            String[] o = orderHash(a.getCertHash(), b.getCertHash());
            String ck = buildConflictKey(o[0], o[1], pr.primaryType.name());
            pr.evidence = buildEvidenceAsset(ctx, o[0], o[1], pr.primaryType, raw);
            pr.evidence.setConflictId(ck);
            pr.evidence.setConflictKey(ck);
            LOG.info("[AUDIT][tx=" + tx + "][phase=EVIDENCE][pair=" + pairLabel + "] prepared key=" + ck + " type=" + pr.primaryType);
        }

        return pr;
    }

    private static void runInheritancePhase(Context ctx, Map<String, CertificateAsset> inlineByHash,
                                            CertificateAsset a, CertificateAsset b,
                                            List<String> raw, List<String> logLines) {
        String txId = ctx.getStub().getTxId();
        if (inlineByHash != null) {
            CertificateAsset pa = resolveParentInline(inlineByHash, a);
            if (pa != null) {
                logLines.add("INHERIT_CHECK child=" + a.getCertHash() + " parent=" + pa.getCertHash());
            }
            appendInheritanceFindings(txId, collectInheritanceViolations(a, pa), raw, logLines);
            CertificateAsset pb = resolveParentInline(inlineByHash, b);
            if (pb != null) {
                logLines.add("INHERIT_CHECK child=" + b.getCertHash() + " parent=" + pb.getCertHash());
            }
            appendInheritanceFindings(txId, collectInheritanceViolations(b, pb), raw, logLines);
        } else {
            CertificateAsset pa = resolveParent(ctx, a);
            if (pa != null) {
                logLines.add("INHERIT_CHECK child=" + a.getCertHash() + " parent=" + pa.getCertHash());
            }
            appendInheritanceFindings(txId, collectInheritanceViolations(a, pa), raw, logLines);
            CertificateAsset pb = resolveParent(ctx, b);
            if (pb != null) {
                logLines.add("INHERIT_CHECK child=" + b.getCertHash() + " parent=" + pb.getCertHash());
            }
            appendInheritanceFindings(txId, collectInheritanceViolations(b, pb), raw, logLines);
        }
    }

    private static ConflictEvidenceAsset buildEvidenceAsset(Context ctx, String ha, String hb, ConflictType primary, List<String> raw) {
        String[] o = orderHash(ha, hb);
        ConflictEvidenceAsset ev = new ConflictEvidenceAsset();
        ev.setCertHashA(o[0]);
        ev.setCertHashB(o[1]);
        ev.setInvolvedCertHashes(new ArrayList<>(List.of(o[0], o[1])));
        ev.setConflictType(primary.name());
        ev.setSeverity(severityFor(primary));
        ev.setRuleReference("rpkiccjava-detect-v3.2.2");
        ev.setConflictDetails(String.join(" | ", raw));
        ev.setDetailsShort(trunc(ev.getConflictDetails(), 512));
        ev.setDetectedAt(txTimestampEpochMs(ctx));
        return ev;
    }

    private static void checkV4PrefixRules(List<Prefix4> la, List<Prefix4> lb, List<String> findings, List<String> log,
                                         String txId, String pairLabel) {
        int hit = 0;
        for (Prefix4 pa : la) {
            for (Prefix4 pb : lb) {
                if (isPrefixContained(pa, pb)) {
                    String line = "IPV4_CONTAINMENT pa=" + pa + " pb=" + pb;
                    addFinding(findings, log, line);
                    hit++;
                    LOG.info("[AUDIT][tx=" + txId + "][phase=IPV4][pair=" + pairLabel + "][rule=CONTAINMENT] " + line);
                } else if (isPrefixOverlappingPartial(pa, pb)) {
                    String line = "IPV4_OVERLAP pa=" + pa + " pb=" + pb;
                    addFinding(findings, log, line);
                    hit++;
                    LOG.info("[AUDIT][tx=" + txId + "][phase=IPV4][pair=" + pairLabel + "][rule=OVERLAP_PARTIAL] " + line);
                } else if (isAdjacentDifferentLengthConflict(pa, pb)) {
                    String line = "IPV4_ADJACENT_LEN pa=" + pa + " pb=" + pb + " (plen differ, ranges touch)";
                    addFinding(findings, log, line);
                    hit++;
                    LOG.info("[AUDIT][tx=" + txId + "][phase=IPV4][pair=" + pairLabel + "][rule=ADJ_LEN] " + line);
                }
            }
        }
        if (hit == 0) {
            LOG.info("[AUDIT][tx=" + txId + "][phase=IPV4][pair=" + pairLabel + "] no v4 prefix conflict");
        }
    }

    private static void checkV6PrefixRules(List<Prefix6> la, List<Prefix6> lb, List<String> findings, List<String> log,
                                         String txId, String pairLabel) {
        int hit = 0;
        for (Prefix6 pa : la) {
            for (Prefix6 pb : lb) {
                if (isPrefixContained(pa, pb)) {
                    String line = "IPV6_CONTAINMENT pa=" + pa + " pb=" + pb;
                    addFinding(findings, log, line);
                    hit++;
                    LOG.info("[AUDIT][tx=" + txId + "][phase=IPV6][pair=" + pairLabel + "][rule=CONTAINMENT] " + line);
                } else if (isPrefixOverlappingPartial(pa, pb)) {
                    String line = "IPV6_OVERLAP pa=" + pa + " pb=" + pb;
                    addFinding(findings, log, line);
                    hit++;
                    LOG.info("[AUDIT][tx=" + txId + "][phase=IPV6][pair=" + pairLabel + "][rule=OVERLAP_PARTIAL] " + line);
                } else if (isAdjacentDifferentLengthConflict(pa, pb)) {
                    String line = "IPV6_ADJACENT_LEN pa=" + pa + " pb=" + pb + " (plen differ, ranges touch)";
                    addFinding(findings, log, line);
                    hit++;
                    LOG.info("[AUDIT][tx=" + txId + "][phase=IPV6][pair=" + pairLabel + "][rule=ADJ_LEN] " + line);
                }
            }
        }
        if (hit == 0) {
            LOG.info("[AUDIT][tx=" + txId + "][phase=IPV6][pair=" + pairLabel + "] no v6 prefix conflict");
        }
    }

    private static void addFinding(List<String> findings, List<String> log, String line) {
        findings.add(line);
        log.add(line);
    }

    private static void checkASConflictOnSharedResource(List<Prefix4> la, List<Prefix4> lb,
                                                        List<Prefix6> l6a, List<Prefix6> l6b,
                                                        List<String> asA, List<String> asB,
                                                        List<String> findings, List<String> log, String txId) {
        TreeSet<Long> setA = normalizeAsLongSet(asA);
        TreeSet<Long> setB = normalizeAsLongSet(asB);
        boolean shared = hasSharedIpResource(la, lb, l6a, l6b);
        if (!shared) {
            LOG.info("[AUDIT][tx=" + txId + "][phase=AS] skip (no shared IPv4/IPv6 resource or empty prefix lists)");
            return;
        }
        if (setA.isEmpty() || setB.isEmpty()) {
            LOG.info("[AUDIT][tx=" + txId + "][phase=AS] skip (one or both AS sets empty after normalizeAsLongSet)");
            return;
        }
        TreeSet<Long> inter = new TreeSet<>(setA);
        inter.retainAll(setB);
        if (isASConflict(setA, setB)) {
            String f = "AS_DISAGREE_ON_SHARED_IP sharedIp=true asIntersection=" + inter
                    + " asA=" + setA + " asB=" + setB + " equalSets=" + setA.equals(setB);
            findings.add(f);
            log.add(f);
            LOG.info("[AUDIT][tx=" + txId + "][phase=AS][rule=MISMATCH] " + f);
        } else {
            LOG.info("[AUDIT][tx=" + txId + "][phase=AS] no AS conflict (same non-empty AS set under shared IP)");
        }
    }

    /** 按 {@link ConflictType} 声明序取最严重命中（与开题优先级一致）。 */
    private static ConflictType worstConflictType(List<FindingEntry> structured) {
        ConflictType w = ConflictType.NONE;
        for (FindingEntry fe : structured) {
            w = maxSeverity(w, fe.type);
        }
        return w;
    }

    /**
     * 将链下/载荷中的 AS 字符串规范为升序 {@link TreeSet}{@code <Long>}：去 AS 前缀、去空白、跳过非数字项。
     */
    private static TreeSet<Long> normalizeAsLongSet(List<String> raw) {
        TreeSet<Long> s = new TreeSet<>();
        if (raw == null) {
            return s;
        }
        for (String x : new TreeSet<>(raw)) {
            if (x == null || x.isBlank()) {
                continue;
            }
            String t = x.trim().toUpperCase(Locale.ROOT).replaceFirst("^AS", "").trim();
            try {
                s.add(Long.parseLong(t));
            } catch (NumberFormatException e) {
                LOG.fine("skip non-numeric AS token: " + x);
            }
        }
        return s;
    }

    /** 从继承类 detail 中抽取 child= 后的 certHash，供 findings 结构化字段使用。 */
    private static String extractChildCertHashIfInherit(String line) {
        if (line == null || !line.contains("INHERIT_") || !line.contains("child=")) {
            return null;
        }
        int c = line.indexOf("child=");
        int start = c + "child=".length();
        int sp = line.indexOf(' ', start);
        int end = sp < 0 ? line.length() : sp;
        return line.substring(start, end).trim();
    }

    /** 与执行分段一致：INHERIT / AS / IPV4 / IPV6 / MISSING。 */
    private static String inferRulePhase(String line) {
        if (line == null) {
            return "UNKNOWN";
        }
        if (line.startsWith("MISSING_ASSET")) {
            return "MISSING";
        }
        if (line.startsWith("INHERIT_")) {
            return "INHERIT";
        }
        if (line.startsWith("AS_DISAGREE")) {
            return "AS";
        }
        if (line.startsWith("IPV4_")) {
            return "IPV4";
        }
        if (line.startsWith("IPV6_")) {
            return "IPV6";
        }
        return "UNKNOWN";
    }

    /** 抽取便于展示的冲突范围片段（前缀对、AS 集合或继承子前缀等）。 */
    private static String inferConflictScope(String line) {
        if (line == null) {
            return "";
        }
        if (line.contains(" pa=")) {
            int p = line.indexOf(" pa=");
            return line.substring(p).trim();
        }
        if (line.contains("childPrefix=")) {
            int p = line.indexOf("childPrefix=");
            return line.substring(p).trim();
        }
        if (line.contains("asA=")) {
            int p = line.indexOf("asA=");
            return line.substring(p).trim();
        }
        return line.length() > 240 ? line.substring(0, 240) + "…" : line;
    }

    private static ConflictType conflictTypeOfFinding(String f) {
        if (f == null) {
            return ConflictType.NONE;
        }
        if (f.startsWith("MISSING_ASSET")) {
            return ConflictType.MISSING_ASSET;
        }
        if (f.startsWith("IPV4_CONTAINMENT") || f.startsWith("IPV6_CONTAINMENT")) {
            return ConflictType.PREFIX_CONTAINMENT;
        }
        if (f.startsWith("IPV4_OVERLAP") || f.startsWith("IPV6_OVERLAP")) {
            return ConflictType.PREFIX_OVERLAP;
        }
        if (f.startsWith("IPV4_ADJACENT_LEN") || f.startsWith("IPV6_ADJACENT_LEN")) {
            return ConflictType.PREFIX_ADJACENT_LENGTH_MISMATCH;
        }
        if (f.startsWith("AS_DISAGREE")) {
            return ConflictType.AS_MISMATCH;
        }
        if (f.startsWith("INHERIT_")) {
            return ConflictType.INHERITANCE_VIOLATION;
        }
        return ConflictType.NONE;
    }

    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public String queryCertificate(final Context ctx, final String certHash) {
        return queryCertificateByHash(ctx, certHash);
    }

    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public String queryCertificateByHash(final Context ctx, final String certHash) {
        CertificateAsset c = readCert(ctx, certHash);
        return c == null ? "{}" : GSON.toJson(c);
    }

    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public String queryConflicts(final Context ctx) {
        List<String> out = new ArrayList<>();
        try (QueryResultsIterator<KeyValue> it = ctx.getStub().getStateByRange("CONFLICT|", "CONFLICT|~")) {
            for (KeyValue kv : it) {
                out.add(new String(kv.getValue(), StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            throw new ChaincodeException("queryConflicts: " + e.getMessage());
        }
        return GSON.toJson(out);
    }

    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public String getAuditHistory(final Context ctx, final String certHash) {
        return historyJson(ctx, certHash);
    }

    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public String getHistoryForKey(final Context ctx, final String certHash) {
        return historyJson(ctx, certHash);
    }

    private static String historyJson(Context ctx, String certHash) {
        String key = certKey(certHash);
        List<Map<String, Object>> versions = new ArrayList<>();
        try (QueryResultsIterator<KeyModification> hist = ctx.getStub().getHistoryForKey(key)) {
            for (KeyModification km : hist) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("txId", km.getTxId());
                row.put("isDelete", km.isDeleted());
                if (km.getTimestamp() != null) {
                    var t = km.getTimestamp();
                    row.put("timestampNs", t.getEpochSecond() * 1_000_000_000L + t.getNano());
                } else {
                    row.put("timestampNs", 0L);
                }
                versions.add(row);
            }
        } catch (Exception e) {
            throw new ChaincodeException("getHistoryForKey failed: " + e.getMessage());
        }
        return GSON.toJson(versions);
    }

    private static void log(Context ctx, String msg) {
        LOG.info("[tx=" + ctx.getStub().getTxId() + "] " + msg);
    }

    private static String certBatchResultJson(Context ctx, int stored, List<String> writtenHashes) {
        String tx = ctx.getStub().getTxId();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("txId", tx);
        List<String> txIds = new ArrayList<>();
        for (int i = 0; i < stored; i++) {
            txIds.add(tx);
        }
        m.put("txIds", txIds);
        m.put("stored", stored);
        m.put("writtenCertHashes", writtenHashes);
        return GSON.toJson(m);
    }

    private static String evidenceBatchResultJson(Context ctx, int stored, List<String> keys) {
        String tx = ctx.getStub().getTxId();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("txId", tx);
        m.put("txIds", List.of(tx));
        m.put("stored", stored);
        m.put("writtenConflictKeys", keys);
        return GSON.toJson(m);
    }

    private static String stateConflictId(ConflictEvidenceAsset rec) {
        if (rec.getConflictId() != null && !rec.getConflictId().isBlank()) {
            return rec.getConflictId().trim();
        }
        if (rec.getConflictKey() != null && !rec.getConflictKey().isBlank()) {
            return rec.getConflictKey().trim();
        }
        return null;
    }

    private static void normalizeEvidence(Context ctx, ConflictEvidenceAsset rec, String stateId) {
        rec.setConflictKey(stateId);
        rec.setConflictId(stateId);
        if (rec.getInvolvedCertHashes() == null) {
            rec.setInvolvedCertHashes(new ArrayList<>());
        }
        if (rec.getInvolvedCertHashes().isEmpty()
                && rec.getCertHashA() != null && rec.getCertHashB() != null) {
            String[] o = orderHash(rec.getCertHashA(), rec.getCertHashB());
            rec.setInvolvedCertHashes(new ArrayList<>(List.of(o[0], o[1])));
        }
        if (rec.getDetectedAt() <= 0L) {
            rec.setDetectedAt(txTimestampEpochMs(ctx));
        }
    }

    /**
     * 交易模拟在各 endorser 上必须产生相同读写集：时间戳只能来自 Fabric 提供的交易时间。
     */
    private static long txTimestampEpochMs(Context ctx) {
        Instant instant = ctx.getStub().getTxTimestamp();
        if (instant == null) {
            return 0L;
        }
        return instant.toEpochMilli();
    }

    private static CertificateAsset readCert(Context ctx, String hash) {
        if (hash == null || hash.isBlank()) {
            return null;
        }
        byte[] raw = ctx.getStub().getState(certKey(hash));
        if (raw == null || raw.length == 0) {
            return null;
        }
        return GSON.fromJson(new String(raw, StandardCharsets.UTF_8), CertificateAsset.class);
    }

    private static String buildConflictKey(String ha, String hb, String type) {
        String[] o = orderHash(ha, hb);
        return (o[0] + "_" + o[1] + "_" + type).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
    }

    private static String[] orderHash(String ha, String hb) {
        String x = ha.compareToIgnoreCase(hb) <= 0 ? ha.trim() : hb.trim();
        String y = ha.compareToIgnoreCase(hb) <= 0 ? hb.trim() : ha.trim();
        return new String[]{x, y};
    }

    private static String severityFor(ConflictType t) {
        if (t == ConflictType.INHERITANCE_VIOLATION || t == ConflictType.MISSING_ASSET) {
            return "CRITICAL";
        }
        if (t == ConflictType.AS_MISMATCH) {
            return "HIGH";
        }
        return "HIGH";
    }

    private static String trunc(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static List<Prefix4> parseAllV4(List<String> cidrs) {
        List<Prefix4> out = new ArrayList<>();
        if (cidrs == null) {
            return out;
        }
        TreeSet<String> sorted = new TreeSet<>(cidrs);
        for (String c : sorted) {
            if (c == null || c.isBlank()) {
                continue;
            }
            try {
                out.add(Prefix4.parse(c.trim()));
            } catch (Exception e) {
                LOG.fine("skip bad v4 cidr: " + c);
            }
        }
        return out;
    }

    private static List<Prefix6> parseAllV6(List<String> cidrs) {
        List<Prefix6> out = new ArrayList<>();
        if (cidrs == null) {
            return out;
        }
        TreeSet<String> sorted = new TreeSet<>(cidrs);
        for (String c : sorted) {
            if (c == null || c.isBlank()) {
                continue;
            }
            try {
                out.add(Prefix6.parse(c.trim()));
            } catch (Exception e) {
                LOG.fine("skip bad v6 cidr: " + c);
            }
        }
        return out;
    }

    private static boolean coveredByAnyV4(Prefix4 c, List<Prefix4> parents) {
        for (Prefix4 p : parents) {
            if (Prefix4.contains(p, c)) {
                return true;
            }
        }
        return false;
    }

    private static boolean coveredByAnyV6(Prefix6 c, List<Prefix6> parents) {
        for (Prefix6 p : parents) {
            if (Prefix6.contains(p, c)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 父解析：{@code parentRef} 为父 cert_hash 或任意可命中世界状态的键；若仅有 {@code parentCertId}，
     * 则尝试以 {@code String.valueOf(parentCertId)} 为键读状态（与链下锚定约定一致）。
     */
    private static CertificateAsset resolveParent(Context ctx, CertificateAsset child) {
        if (child == null || child.getCertHash() == null) {
            return null;
        }
        String ref = child.getParentRef();
        if (ref != null && !ref.isBlank()) {
            String r = ref.trim();
            if (looksLikeCertHash(r)) {
                CertificateAsset p = readCert(ctx, r);
                if (p != null && !p.getCertHash().equalsIgnoreCase(child.getCertHash())) {
                    return p;
                }
            }
            CertificateAsset p2 = readCert(ctx, r);
            if (p2 != null && !p2.getCertHash().equalsIgnoreCase(child.getCertHash())) {
                return p2;
            }
        }
        if (child.getParentCertId() != null) {
            String key = String.valueOf(child.getParentCertId());
            CertificateAsset p3 = readCert(ctx, key);
            if (p3 != null && !p3.getCertHash().equalsIgnoreCase(child.getCertHash())) {
                return p3;
            }
        }
        return null;
    }

    private static CertificateAsset resolveParentInline(Map<String, CertificateAsset> byLower, CertificateAsset child) {
        if (child == null || child.getCertHash() == null) {
            return null;
        }
        if (child.getParentRef() != null && !child.getParentRef().isBlank()) {
            CertificateAsset p = byLower.get(child.getParentRef().trim().toLowerCase(Locale.ROOT));
            if (p != null && !p.getCertHash().equalsIgnoreCase(child.getCertHash())) {
                return p;
            }
        }
        if (child.getParentCertId() != null) {
            CertificateAsset p2 = byLower.get(String.valueOf(child.getParentCertId()).toLowerCase(Locale.ROOT));
            if (p2 != null && !p2.getCertHash().equalsIgnoreCase(child.getCertHash())) {
                return p2;
            }
        }
        return null;
    }

    private static boolean looksLikeCertHash(String r) {
        if (r.length() < 32) {
            return false;
        }
        return r.matches("(?i)[0-9a-f]{32,128}");
    }

    // ————————————————————————————————————————————————————————————————
    // IPv4 CIDR：区间、相邻（无符号 32 位）
    // ————————————————————————————————————————————————————————————————

    static final class Prefix4 {
        final long network;
        final int plen;

        Prefix4(long network, int plen) {
            this.network = network;
            this.plen = plen;
        }

        static Prefix4 parse(String cidr) {
            int slash = cidr.indexOf('/');
            String host = cidr.substring(0, slash).trim();
            int pl = Integer.parseInt(cidr.substring(slash + 1).trim());
            String[] oct = host.split("\\.");
            long v = 0;
            for (int i = 0; i < 4; i++) {
                v = (v << 8) | (Long.parseLong(oct[i].trim()) & 0xff);
            }
            long mask = maskFor(pl);
            return new Prefix4(v & mask, pl);
        }

        /**
         * outer 的 CIDR 块在整数轴上完全覆盖 inner 的块（子网关系）。
         * 必要条件 {@code plen(outer) ≤ plen(inner)}，充分条件为区间包含。
         */
        static boolean cidrIntervalSubset(Prefix4 outer, Prefix4 inner) {
            if (outer == null || inner == null || outer.plen > inner.plen) {
                return false;
            }
            BigInteger sO = blockStartU32(outer);
            BigInteger eO = blockEndU32(outer);
            BigInteger sI = blockStartU32(inner);
            BigInteger eI = blockEndU32(inner);
            return sI.compareTo(sO) >= 0 && eI.compareTo(eO) <= 0;
        }

        /** 两 CIDR 块闭区间相交非空：{@code max(Sa,Sb) ≤ min(Ea,Eb)}。 */
        static boolean cidrIntervalsIntersect(Prefix4 a, Prefix4 b) {
            if (a == null || b == null) {
                return false;
            }
            BigInteger sa = blockStartU32(a);
            BigInteger ea = blockEndU32(a);
            BigInteger sb = blockStartU32(b);
            BigInteger eb = blockEndU32(b);
            return sa.max(sb).compareTo(ea.min(eb)) <= 0;
        }

        static boolean contains(Prefix4 parent, Prefix4 child) {
            return cidrIntervalSubset(parent, child);
        }

        /** 与 {@link #cidrIntervalsIntersect} 等价（保留方法名供读链码者对照文献）。 */
        static boolean overlaps(Prefix4 a, Prefix4 b) {
            return cidrIntervalsIntersect(a, b);
        }

        /** 块 [start,end] 在整数轴上紧挨：endA+1 == startB 或 endB+1 == startA（无符号 32 位）。 */
        static boolean rangesAdjacent(Prefix4 a, Prefix4 b) {
            BigInteger sa = blockStartU32(a);
            BigInteger ea = blockEndU32(a);
            BigInteger sb = blockStartU32(b);
            BigInteger eb = blockEndU32(b);
            return ea.add(BigInteger.ONE).equals(sb) || eb.add(BigInteger.ONE).equals(sa);
        }

        private static BigInteger u32(long x) {
            return BigInteger.valueOf(x).and(BigInteger.valueOf(0xffff_ffffL));
        }

        static BigInteger blockStartU32(Prefix4 p) {
            long ma = maskFor(p.plen);
            return u32(p.network & ma);
        }

        static BigInteger blockEndU32(Prefix4 p) {
            int hostBits = 32 - p.plen;
            if (hostBits <= 0) {
                return blockStartU32(p);
            }
            return blockStartU32(p).add(BigInteger.ONE.shiftLeft(hostBits)).subtract(BigInteger.ONE);
        }

        static long maskFor(int prefixLen) {
            if (prefixLen <= 0) {
                return 0L;
            }
            if (prefixLen >= 32) {
                return 0xffff_ffffL;
            }
            return 0xffff_ffffL << (32 - prefixLen);
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "%d/%d", network & 0xffff_ffffL, plen);
        }
    }

    // ————————————————————————————————————————————————————————————————
    // IPv6 CIDR：128 位无符号
    // ————————————————————————————————————————————————————————————————

    static final class Prefix6 {
        final BigInteger network;
        final int plen;

        Prefix6(BigInteger network, int plen) {
            this.network = network;
            this.plen = plen;
        }

        static Prefix6 parse(String cidr) throws UnknownHostException {
            int slash = cidr.indexOf('/');
            String host = cidr.substring(0, slash).trim();
            int pl = Integer.parseInt(cidr.substring(slash + 1).trim());
            byte[] raw = InetAddress.getByName(host).getAddress();
            if (raw.length != 16) {
                throw new IllegalArgumentException("not ipv6");
            }
            BigInteger ip = new BigInteger(1, raw);
            BigInteger mask = mask128(pl);
            return new Prefix6(ip.and(mask), pl);
        }

        static BigInteger mask128(int plen) {
            if (plen <= 0) {
                return BigInteger.ZERO;
            }
            if (plen >= 128) {
                return maxU128();
            }
            BigInteger lowOnes = BigInteger.ONE.shiftLeft(128 - plen).subtract(BigInteger.ONE);
            return maxU128().xor(lowOnes);
        }

        static BigInteger maxU128() {
            return BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE);
        }

        /** IPv6 版 {@link Prefix4#cidrIntervalSubset}。 */
        static boolean cidrIntervalSubset(Prefix6 outer, Prefix6 inner) {
            if (outer == null || inner == null || outer.plen > inner.plen) {
                return false;
            }
            BigInteger sO = blockStart(outer);
            BigInteger eO = blockEnd(outer);
            BigInteger sI = blockStart(inner);
            BigInteger eI = blockEnd(inner);
            return sI.compareTo(sO) >= 0 && eI.compareTo(eO) <= 0;
        }

        /** IPv6 版 {@link Prefix4#cidrIntervalsIntersect}。 */
        static boolean cidrIntervalsIntersect(Prefix6 a, Prefix6 b) {
            if (a == null || b == null) {
                return false;
            }
            BigInteger sa = blockStart(a);
            BigInteger ea = blockEnd(a);
            BigInteger sb = blockStart(b);
            BigInteger eb = blockEnd(b);
            return sa.max(sb).compareTo(ea.min(eb)) <= 0;
        }

        static boolean contains(Prefix6 parent, Prefix6 child) {
            return cidrIntervalSubset(parent, child);
        }

        static boolean overlaps(Prefix6 a, Prefix6 b) {
            return cidrIntervalsIntersect(a, b);
        }

        static boolean rangesAdjacent(Prefix6 a, Prefix6 b) {
            BigInteger sa = blockStart(a);
            BigInteger ea = blockEnd(a);
            BigInteger sb = blockStart(b);
            BigInteger eb = blockEnd(b);
            return ea.add(BigInteger.ONE).equals(sb) || eb.add(BigInteger.ONE).equals(sa);
        }

        static BigInteger blockStart(Prefix6 p) {
            return p.network.and(mask128(p.plen));
        }

        static BigInteger blockEnd(Prefix6 p) {
            BigInteger host = maxU128().xor(mask128(p.plen));
            return p.network.or(host);
        }

        @Override
        public String toString() {
            return network.toString(16) + "/" + plen;
        }
    }

    static final class PairDetectResult {
        final List<String> rawFindings = new ArrayList<>();
        final List<FindingEntry> structuredFindings = new ArrayList<>();
        ConflictType primaryType = ConflictType.NONE;
        String persistedEvidenceKey;
        ConflictEvidenceAsset evidence;
    }

    public static final class FindingEntry {
        /** {@link ConflictType} 名 */
        public String type;
        /** 完整机器可读描述行 */
        public String detail;
        /** 本对比较中的两枚证书 */
        public String certHashA;
        public String certHashB;
        /** 冲突所涉 IP/AS 范围摘要，如 {@code pa=... pb=...} 或 {@code childPrefix=...} */
        public String conflictScope;
        /** 继承类命中时标识「子」证书 cert_hash；非继承则为 null */
        public String involvedCertHashChild;
        /** 执行分段：INHERIT / AS / IPV4 / IPV6 / MISSING */
        public String rulePhase;
    }

    public static final class ConflictDetectResult {
        public String txId;
        public String verdict;
        public String primaryConflictType;
        public String legacyWorstCode;
        public List<FindingEntry> findings = new ArrayList<>();
        public List<String> rawFindings = new ArrayList<>();
        public List<String> log = new ArrayList<>();
        public String persistedEvidenceKey;
        public List<String> persistedEvidenceKeys = new ArrayList<>();
        public List<String> involvedCertHashes = new ArrayList<>();
        public int pairsExamined;
        public int conflictingPairs;
    }
}
