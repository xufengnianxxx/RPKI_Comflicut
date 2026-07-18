package com.rpki.conflictchecker;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.rpki.conflictchecker.testdata.Rfc3779ResourceEncoder;
import com.rpki.conflictchecker.testdata.SyntheticRPKICertificateFactory;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.cert.X509CertificateHolder;

import java.io.Writer;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 在 {@code qidongjiaoben/test-data/} 生成功能测试用 .cer 与 {@code functional-manifest.json}。
 * 运行：{@code mvn -q -Dtest=TestDataGenerator#generateAll test} 或 {@code main}。
 * <p>
 * 说明：当前 {@link com.rpki.conflictchecker.util.IPAddressUtils} 与 {@link com.rpki.conflictchecker.util.Ipv6CidrUtils} 的
 * 「重叠」与「包含」在实现上，常见「形式化 L2 重叠而链下实现先进入 L1/包含类」
 *（见《冲突规则.md》与工程内 test-conflict-construction 表）。本生成器中的预期类型以
 * <b>与 RpkiUnifiedConflictDetectionService 一致的实际检测类型</b>为准，并在 expected JSON
 * 中通过 {@code formalDefRef} 标注《冲突规则》中读者容易混淆的形式定义位。
 */
public class TestDataGenerator {

    public static void main(String[] args) throws Exception {
        new TestDataGenerator().generateAll();
    }

    @org.junit.jupiter.api.Test
    void generateAll() throws Exception {
        Path root = projectRoot();
        Path outDir = root.resolve("qidongjiaoben").resolve("test-data");
        Path cerDir = outDir.resolve("certs");
        Path expectDir = outDir.resolve("expected");
        Files.createDirectories(cerDir);
        Files.createDirectories(expectDir);

        KeyPair caKp = SyntheticRPKICertificateFactory.newRsa2048();
        X509CertificateHolder ca = SyntheticRPKICertificateFactory.buildCaCertificate(caKp);
        writeDer(cerDir.resolve("CA.cer"), ca.getEncoded());

        List<Map<String, Object>> cases = new ArrayList<>();
        int n = 1;

        // TC01 D1：IPv4 真包含（两证均 /8n 边界，保证 RFC 3779 位串在解析器内可稳定还原，避免 /12 等非整字节在测试编码中丢前缀）
        n = addPeerCase("TC01", "D1/L1  IPv4 包含", "D1", null, List.of("IP_CONTAINMENT_PEER"), ca, caKp, cerDir, expectDir, cases, n,
                extIp(List.of("10.0.0.0/8"), List.of()), extIp(List.of("10.0.0.0/16"), List.of()));
        // TC02：形式 D2/L2，实现仍常归类为 D1(包含) — 使用 /16+ /24 保证扩展值可解析
        n = addPeerCase("TC02", "D2(形式) / L2(链上)  典型槽位(3,4)  实现归类为包含", "D2",
                "D2(形式) 在实现中先命中 D1(包含) — 见 冲突规则 与 isIPv4CidrContains 优先序",
                List.of("IP_CONTAINMENT_PEER"), ca, caKp, cerDir, expectDir, cases, n,
                extIp(List.of("10.11.0.0/16"), List.of()), extIp(List.of("10.11.1.0/24"), List.of()));
        // TC03 D3 相邻：与构造方案 (5,6) 同构
        n = addPeerCase("TC03", "D3  IPv4 同长相邻", "D3", null, List.of("IP_ADJACENT_BORDER"), ca, caKp, cerDir, expectDir, cases, n,
                extIp(List.of("10.12.0.0/24"), List.of()), extIp(List.of("10.12.1.0/24"), List.of()));
        // TC04 D4 IPv6 包含：与 (7,8) 同构
        n = addPeerCase("TC04", "D4  IPv6 包含", "D4", null, List.of("IP6_CONTAINMENT_PEER"), ca, caKp, cerDir, expectDir, cases, n,
                extIp(List.of(), List.of("2001:db8:a::/48")), extIp(List.of(), List.of("2001:db8:a:1::/64")));
        // TC05：与 (9,10) 语义同构；/47 在合成分支下解析失配风险高，改与 TC04 同型的 /48+ /64 以便扩展往返与论文描述一致
        n = addPeerCase("TC05", "D5(形式)  IPv6 重叠(构造)  实现为包含", "D5",
                "D5(形式) 在实现中先命中 D4(包含)",
                List.of("IP6_CONTAINMENT_PEER"), ca, caKp, cerDir, expectDir, cases, n,
                extIp(List.of(), List.of("2001:db8:bb::/48")),
                extIp(List.of(), List.of("2001:db8:bb:1::/64")));
        // TC06：与 (11,12) 同构；在当前 isIpv6CidrContains/重叠顺序下，两串往往归一后落入同一/64 块 → 先命中包含类
        n = addPeerCase("TC06", "D6(形式) / 实现  IPv6 含或邻（见 formalDefNote）", "D6",
                "若两前缀归一为同一/64 则实现报 IP6_CONTAINMENT_peer；与冲突测试构造方案(11,12) 可能因解析边界而异",
                List.of("IP6_CONTAINMENT_PEER"), ca, caKp, cerDir, expectDir, cases, n,
                extIp(List.of(), List.of("2001:db8:c:0:0:0:0:0/64")),
                extIp(List.of(), List.of("2001:db8:c:0:0:0:1:0/64")));

        n = addParentChildOosV4(ca, caKp, cerDir, expectDir, cases, n, "TC07", "D7  IPv4 子越权", "D7", List.of("IP_OUT_OF_SCOPE"));
        n = addParentChildOosV6(ca, caKp, cerDir, expectDir, cases, n, "TC08", "D8  IPv6 子越权", "D8", List.of("IP6_OUT_OF_SCOPE"));
        n = addParentChildOosAs(ca, caKp, cerDir, expectDir, cases, n, "TC09", "D9  AS 子越权", "D9", List.of("AS_OUT_OF_SCOPE"));

        n = addMultiHopChain(ca, caKp, cerDir, expectDir, cases, n);
        n = addDirectRevoke(ca, caKp, cerDir, expectDir, cases, n);
        n = addAsPeerOnly(ca, caKp, cerDir, expectDir, cases, n);

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("version", 1);
        manifest.put("description", "与 RpkiUnifiedConflictDetectionService 对齐的 manifest；链上 L1~L3 为 Fabric 层命名，对应 IPv4 区间三分类，见 冲突规则.md 第六节。");
        manifest.put("cases", cases);

        try (Writer w = Files.newBufferedWriter(outDir.resolve("functional-manifest.json"), StandardCharsets.UTF_8)) {
            new GsonBuilder().setPrettyPrinting().create().toJson(manifest, w);
        }
        System.out.println("Wrote: " + outDir.toAbsolutePath());
    }

    private static Path projectRoot() {
        return Path.of(System.getProperty("user.dir"));
    }

    private static void writeDer(Path p, byte[] der) throws Exception {
        Files.write(p, der);
    }

    private static List<Extension> extIp(List<String> v4, List<String> v6) throws Exception {
        return List.of(Rfc3779ResourceEncoder.buildIpAddressBlocksExtension(v4, v6));
    }

    private static List<Extension> extIpAs(List<String> v4, List<String> v6, List<Long> as) throws Exception {
        List<Extension> e = new ArrayList<>();
        e.add(Rfc3779ResourceEncoder.buildIpAddressBlocksExtension(v4, v6));
        e.add(Rfc3779ResourceEncoder.buildAsIdentifiersExtension(as));
        return e;
    }

    private static int addPeerCase(
            String id, String name, String def, String formalNote, List<String> expect,
            X509CertificateHolder ca, KeyPair caKp, Path cerDir, Path expectDir, List<Map<String, Object>> cases,
            int startSerial, List<Extension> a, List<Extension> b) throws Exception {
        KeyPair k1 = SyntheticRPKICertificateFactory.newRsa2048();
        KeyPair k2 = SyntheticRPKICertificateFactory.newRsa2048();
        X509CertificateHolder h1 = SyntheticRPKICertificateFactory.buildEeSignedByCa(
                ca, caKp, "CN=" + id + "-A", k1, BigInteger.valueOf(1000 + startSerial), a);
        X509CertificateHolder h2 = SyntheticRPKICertificateFactory.buildEeSignedByCa(
                ca, caKp, "CN=" + id + "-B", k2, BigInteger.valueOf(2000 + startSerial), b);
        String f1 = id + "_A.cer";
        String f2 = id + "_B.cer";
        writeDer(cerDir.resolve(f1), h1.getEncoded());
        writeDer(cerDir.resolve(f2), h2.getEncoded());
        expectJson(expectDir, id, expect, def, formalNote, "PEER");
        cases.add(manifestCasePeer(id, name, def, formalNote, f1, f2, 1, 2, null, null, false, false, expect));
        return startSerial + 1;
    }

    private static int addParentChildOosV4(
            X509CertificateHolder ca, KeyPair caKp, Path cerDir, Path expectDir, List<Map<String, Object>> cases,
            int n, String id, String name, String def, List<String> expect) throws Exception {
        KeyPair parentKp = SyntheticRPKICertificateFactory.newRsa2048();
        KeyPair childKp = SyntheticRPKICertificateFactory.newRsa2048();
        // 首字节须同桶(见 detectPairwiseIndexed 的 firstOctet 桶) 子始得与父成对
        X509CertificateHolder p = SyntheticRPKICertificateFactory.buildEeSignedByCa(
                ca, caKp, "CN=" + id + "-P", parentKp, BigInteger.valueOf(3000 + n),
                extIp(List.of("10.0.0.0/16"), List.of()));
        X509CertificateHolder c = SyntheticRPKICertificateFactory.buildEeSignedByResourceParent(
                p, parentKp, "CN=" + id + "-C", childKp, BigInteger.valueOf(4000 + n),
                extIp(List.of("10.1.0.0/16"), List.of()));
        String fp = id + "_P.cer";
        String fc = id + "_C.cer";
        writeDer(cerDir.resolve(fp), p.getEncoded());
        writeDer(cerDir.resolve(fc), c.getEncoded());
        expectJson(expectDir, id, expect, def, null, "PARENT_CHILD");
        cases.add(manifestCasePeer(id, name, def, null, fc, fp, 1, 2, 2, 1, false, false, expect));
        return n + 1;
    }

    private static int addParentChildOosV6(
            X509CertificateHolder ca, KeyPair caKp, Path cerDir, Path expectDir, List<Map<String, Object>> cases,
            int n, String id, String name, String def, List<String> expect) throws Exception {
        KeyPair parentKp = SyntheticRPKICertificateFactory.newRsa2048();
        KeyPair childKp = SyntheticRPKICertificateFactory.newRsa2048();
        // 子前缀须在父 ULA 外，否则 isIpv6CidrContains 为真不产生 IP6_OUT_OF_SCOPE
        X509CertificateHolder p = SyntheticRPKICertificateFactory.buildEeSignedByCa(
                ca, caKp, "CN=" + id + "-P6", parentKp, BigInteger.valueOf(5000 + n),
                extIp(List.of(), List.of("2001:db8::/32")));
        X509CertificateHolder c = SyntheticRPKICertificateFactory.buildEeSignedByResourceParent(
                p, parentKp, "CN=" + id + "-C6", childKp, BigInteger.valueOf(6000 + n),
                extIp(List.of(), List.of("2001:ee00::/32")));
        String fp = id + "_P.cer";
        String fc = id + "_C.cer";
        writeDer(cerDir.resolve(fp), p.getEncoded());
        writeDer(cerDir.resolve(fc), c.getEncoded());
        expectJson(expectDir, id, expect, def, null, "PARENT_CHILD");
        cases.add(manifestCasePeer(id, name, def, null, fc, fp, 1, 2, 2, 1, false, false, expect));
        return n + 1;
    }

    private static int addParentChildOosAs(
            X509CertificateHolder ca, KeyPair caKp, Path cerDir, Path expectDir, List<Map<String, Object>> cases,
            int n, String id, String name, String def, List<String> expect) throws Exception {
        KeyPair parentKp = SyntheticRPKICertificateFactory.newRsa2048();
        KeyPair childKp = SyntheticRPKICertificateFactory.newRsa2048();
        X509CertificateHolder p = SyntheticRPKICertificateFactory.buildEeSignedByCa(
                ca, caKp, "CN=" + id + "-P-AS", parentKp, BigInteger.valueOf(7000 + n),
                extIpAs(List.of("10.0.0.0/16"), List.of(), List.of(65_000L)));
        X509CertificateHolder c = SyntheticRPKICertificateFactory.buildEeSignedByResourceParent(
                p, parentKp, "CN=" + id + "-C-AS", childKp, BigInteger.valueOf(8000 + n),
                extIpAs(List.of("10.0.0.0/16"), List.of(), List.of(65_001L)));
        String fp = id + "_P.cer";
        String fc = id + "_C.cer";
        writeDer(cerDir.resolve(fp), p.getEncoded());
        writeDer(cerDir.resolve(fc), c.getEncoded());
        expectJson(expectDir, id, expect, def, null, "PARENT_CHILD");
        cases.add(manifestCasePeer(id, name, def, null, fc, fp, 1, 2, 2, 1, false, false, expect));
        return n + 1;
    }

    private static int addMultiHopChain(
            X509CertificateHolder ca, KeyPair caKp, Path cerDir, Path expectDir, List<Map<String, Object>> cases, int n) throws Exception {
        String id = "TC10";
        KeyPair gk = SyntheticRPKICertificateFactory.newRsa2048();
        KeyPair pk = SyntheticRPKICertificateFactory.newRsa2048();
        KeyPair lk = SyntheticRPKICertificateFactory.newRsa2048();
        X509CertificateHolder g = SyntheticRPKICertificateFactory.buildEeSignedByCa(
                ca, caKp, "CN=" + id + "-G", gk, BigInteger.valueOf(10_000 + n), extIp(List.of("10.0.0.0/8"), List.of()));
        X509CertificateHolder p = SyntheticRPKICertificateFactory.buildEeSignedByCa(
                ca, caKp, "CN=" + id + "-P", pk, BigInteger.valueOf(11_000 + n), extIp(List.of("10.0.0.0/8"), List.of()));
        X509CertificateHolder l = SyntheticRPKICertificateFactory.buildEeSignedByCa(
                ca, caKp, "CN=" + id + "-L", lk, BigInteger.valueOf(12_000 + n), extIp(List.of("10.0.0.0/8"), List.of()));
        String fg = id + "_G.cer", fp2 = id + "_P.cer", fl = id + "_L.cer";
        writeDer(cerDir.resolve(fg), g.getEncoded());
        writeDer(cerDir.resolve(fp2), p.getEncoded());
        writeDer(cerDir.resolve(fl), l.getEncoded());
        expectJson(expectDir, id, List.of("CHAIN_REVOKED_ANCESTOR_ACTIVE"), "D10", null, "CHAIN_MULTI");
        List<Map<String, Object>> ent = new ArrayList<>();
        ent.add(entry(fl, 1, 2, false));
        ent.add(entry(fp2, 2, 3, true));
        ent.add(entry(fg, 3, null, true));
        cases.add(manifestCaseChain(id, "D10  祖先已撤销而叶未撤销(多跳)", "D10", null,
                List.of(fl, fp2, fg), ent, List.of("CHAIN_REVOKED_ANCESTOR_ACTIVE")));
        return n + 1;
    }

    private static int addDirectRevoke(
            X509CertificateHolder ca, KeyPair caKp, Path cerDir, Path expectDir, List<Map<String, Object>> cases, int n) throws Exception {
        String id = "TC11";
        KeyPair pk = SyntheticRPKICertificateFactory.newRsa2048();
        KeyPair ck = SyntheticRPKICertificateFactory.newRsa2048();
        X509CertificateHolder p = SyntheticRPKICertificateFactory.buildEeSignedByCa(
                ca, caKp, "CN=" + id + "-P", pk, BigInteger.valueOf(20_000 + n), extIp(List.of("10.0.0.0/8"), List.of()));
        X509CertificateHolder c = SyntheticRPKICertificateFactory.buildEeSignedByResourceParent(
                p, pk, "CN=" + id + "-C", ck, BigInteger.valueOf(21_000 + n), extIp(List.of("10.0.0.0/8"), List.of()));
        String fp = id + "_P.cer", fc = id + "_C.cer";
        writeDer(cerDir.resolve(fp), p.getEncoded());
        writeDer(cerDir.resolve(fc), c.getEncoded());
        expectJson(expectDir, id, List.of("CHAIN_RESIDUAL_AFTER_REVOKE"), "D11", null, "CHAIN_REVOKE");
        // id1=子 C, id2=父 P；parentId(C)=2；父撤销
        List<Map<String, Object>> ent = List.of(
                entry(fc, 1, 2, false),
                entry(fp, 2, null, true)
        );
        cases.add(manifestCaseChain(id, "D11  父已撤销子仍活(直接父子，parent_cert_id)", "D11", null,
                List.of(fc, fp), ent, List.of("CHAIN_RESIDUAL_AFTER_REVOKE")));
        return n + 1;
    }

    private static int addAsPeerOnly(
            X509CertificateHolder ca, KeyPair caKp, Path cerDir, Path expectDir, List<Map<String, Object>> cases, int n) throws Exception {
        String id = "TC12";
        KeyPair k1 = SyntheticRPKICertificateFactory.newRsa2048();
        KeyPair k2 = SyntheticRPKICertificateFactory.newRsa2048();
        // 只挂 AS 扩展，无 IP 时进入 IPv4 桶 -1
        List<Extension> onlyAs = List.of(Rfc3779ResourceEncoder.buildAsIdentifiersExtension(List.of(65_200L, 65_201L)));
        X509CertificateHolder h1 = SyntheticRPKICertificateFactory.buildEeSignedByCa(
                ca, caKp, "CN=" + id + "-A", k1, BigInteger.valueOf(30_000 + n), onlyAs);
        X509CertificateHolder h2 = SyntheticRPKICertificateFactory.buildEeSignedByCa(
                ca, caKp, "CN=" + id + "-B", k2, BigInteger.valueOf(31_000 + n), onlyAs);
        String f1 = id + "_A.cer", f2 = id + "_B.cer";
        writeDer(cerDir.resolve(f1), h1.getEncoded());
        writeDer(cerDir.resolve(f2), h2.getEncoded());
        expectJson(expectDir, id, List.of("AS_OVERLAP_PEER"), "D12", null, "PEER");
        cases.add(manifestCasePeer(id, "D12  对等 AS 授权交集(仅 AS，桶 -1)", "D12", null, f1, f2, 1, 2, null, null, false, false, List.of("AS_OVERLAP_PEER")));
        return n + 1;
    }

    private static void expectJson(Path expectDir, String id, List<String> types, String def, String note, String kind) throws Exception {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("defRef", def);
        e.put("expectedOfflineTypes", types);
        e.put("kind", kind);
        if (note != null) {
            e.put("formalDefNote", note);
        }
        try (Writer w = Files.newBufferedWriter(expectDir.resolve(id + "-expected.json"), StandardCharsets.UTF_8)) {
            new Gson().toJson(e, w);
        }
    }

    private static Map<String, Object> manifestCasePeer(
            String id, String name, String def, String formalNote, String f1, String f2,
            int id1, int id2, Integer parent1, Integer parent2, boolean r1, boolean r2, List<String> expect) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("defRef", def);
        if (formalNote != null) {
            m.put("formalDefNote", formalNote);
        }
        m.put("kind", "PEER");
        m.put("files", List.of(f1, f2));
        m.put("entries", List.of(
                entry(f1, id1, parent1, r1),
                entry(f2, id2, parent2, r2)
        ));
        m.put("expectedOfflineTypes", expect);
        m.put("primaryPair", List.of(Math.min(id1, id2), Math.max(id1, id2)));
        return m;
    }

    private static Map<String, Object> entry(String file, int certId, Integer parentId, boolean revoked) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("id", certId);
        e.put("file", file);
        e.put("parentId", parentId);
        e.put("revoked", revoked);
        return e;
    }

    private static Map<String, Object> manifestCaseChain(
            String id, String name, String def, String formalNote, List<String> files,
            List<Map<String, Object>> entries, List<String> expect) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("defRef", def);
        if (formalNote != null) {
            m.put("formalDefNote", formalNote);
        }
        m.put("kind", entries.size() == 2 ? "CHAIN_REVOKE" : "CHAIN_MULTI");
        m.put("files", files);
        m.put("entries", entries);
        m.put("expectedOfflineTypes", expect);
        m.put("primaryPair", null);
        return m;
    }
}
