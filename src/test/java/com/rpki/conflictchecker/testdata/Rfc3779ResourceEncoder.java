package com.rpki.conflictchecker.testdata;

import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.DERBitString;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.DERTaggedObject;
import org.bouncycastle.asn1.x509.Extension;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.util.ArrayList;
import java.util.List;

import static com.rpki.conflictchecker.util.Rfc3779ResourceParser.OID_AS_IDS;
import static com.rpki.conflictchecker.util.Rfc3779ResourceParser.OID_IP_ADDR_BLOCKS;

/**
 * 生成可被子系统 {@code Rfc3779ResourceParser} 正确解析的 ipAddrBlocks / asIdentifiers 扩展（测试专用）。
 * <p>
 * 注意：当前 {@link #prefixBitStringV4} / {@link #prefixBitStringV6} 与 {@link Rfc3779ResourceParser} 的往返在
 *「非 8/16/24/32 等整字节界」的 IPv4 前缀上存在与 BC DERBitString/解析位序的细微差异，功能样例 CIDR
 * 应优先选用整字节或 TC04/TC06 已验证的 IPv6 写法。
 */
public final class Rfc3779ResourceEncoder {

    private Rfc3779ResourceEncoder() {
    }

    public static Extension buildIpAddressBlocksExtension(List<String> ipv4Cidrs, List<String> ipv6Cidrs) throws Exception {
        List<ASN1Encodable> families = new ArrayList<>();
        if (ipv4Cidrs != null && !ipv4Cidrs.isEmpty()) {
            List<ASN1Encodable> ors = new ArrayList<>();
            for (String c : ipv4Cidrs) {
                ors.add(ipv4CidrToAddressPrefix(c));
            }
            families.add(
                    new DERSequence(
                            new ASN1Encodable[] {
                                new DEROctetString(new byte[] {0, 1}),
                                new DERSequence(ors.toArray(ASN1Encodable[]::new))
                            }
                    )
            );
        }
        if (ipv6Cidrs != null && !ipv6Cidrs.isEmpty()) {
            List<ASN1Encodable> ors6 = new ArrayList<>();
            for (String c : ipv6Cidrs) {
                ors6.add(ipv6CidrToAddressPrefix(c));
            }
            families.add(
                    new DERSequence(
                            new ASN1Encodable[] {
                                new DEROctetString(new byte[] {0, 2}),
                                new DERSequence(ors6.toArray(ASN1Encodable[]::new))
                            }
                    )
            );
        }
        if (families.isEmpty()) {
            throw new IllegalArgumentException("至少一个 IPv4 或 IPv6 前缀");
        }
        byte[] inner = new DERSequence(families.toArray(ASN1Encodable[]::new)).getEncoded("DER");
        // extnValue 为 OCTET STRING 的内容字节，即 id-pe-ipAddrBlocks 的 DER
        return new Extension(OID_IP_ADDR_BLOCKS, false, inner);
    }

    /**
     * ASIdentifiers 编码：与 {@link com.rpki.conflictchecker.util.Rfc3779ResourceParser#parseAsResources} 的 SEQUENCE OF TAG[0/1] 结构对齐。
     */
    public static Extension buildAsIdentifiersExtension(List<Long> asNumbers) throws Exception {
        if (asNumbers == null || asNumbers.isEmpty()) {
            throw new IllegalArgumentException("AS 非空");
        }
        org.bouncycastle.asn1.ASN1EncodableVector innerVec = new org.bouncycastle.asn1.ASN1EncodableVector();
        for (Long n : asNumbers) {
            innerVec.add(new ASN1Integer(n));
        }
        // tag 0: asnum -> AsNumbers = SEQUENCE OF ASId (INTEGER) / range，与 RFC 3779 与 parseAsResources 对齐
        DERTaggedObject t0 = new DERTaggedObject(0, new DERSequence(innerVec));
        byte[] v = new DERSequence(t0).getEncoded("DER");
        return new Extension(OID_AS_IDS, false, v);
    }

    private static DERBitString ipv4CidrToAddressPrefix(String cidr) throws Exception {
        String[] p = cidr.split("/");
        if (p.length != 2) {
            throw new IllegalArgumentException(cidr);
        }
        int pl = Integer.parseInt(p[1].trim());
        Inet4Address addr = (Inet4Address) Inet4Address.getByName(p[0].trim());
        return prefixBitStringV4(addr.getAddress(), pl);
    }

    private static DERBitString ipv6CidrToAddressPrefix(String cidr) throws Exception {
        String[] p = cidr.split("/");
        if (p.length != 2) {
            throw new IllegalArgumentException(cidr);
        }
        int pl = Integer.parseInt(p[1].trim());
        Inet6Address addr = (Inet6Address) Inet6Address.getByName(p[0].trim().replaceAll("^\\[|\\]$", ""));
        return prefixBitStringV6(addr.getAddress(), pl);
    }

    private static DERBitString prefixBitStringV4(byte[] ip, int pl) {
        if (pl < 0 || pl > 32) {
            throw new IllegalArgumentException("pl=" + pl);
        }
        if (pl == 0) {
            return new DERBitString(new byte[0], 0);
        }
        int outBytes = (pl + 7) / 8;
        byte[] out = new byte[outBytes];
        int bitPos = 0;
        for (int i = 0; i < 4; i++) {
            for (int b = 7; b >= 0; b--) {
                if (bitPos >= pl) {
                    return new DERBitString(out, out.length * 8 - pl);
                }
                int v = (ip[i] >> b) & 1;
                int bi = bitPos / 8;
                int off = 7 - (bitPos % 8);
                out[bi] |= (byte) (v << off);
                bitPos++;
            }
        }
        return new DERBitString(out, out.length * 8 - pl);
    }

    private static DERBitString prefixBitStringV6(byte[] ip, int pl) {
        if (pl < 0 || pl > 128) {
            throw new IllegalArgumentException("pl=" + pl);
        }
        if (pl == 0) {
            return new DERBitString(new byte[0], 0);
        }
        int outBytes = (pl + 7) / 8;
        byte[] out = new byte[outBytes];
        int bitPos = 0;
        for (int i = 0; i < 16; i++) {
            for (int b = 7; b >= 0; b--) {
                if (bitPos >= pl) {
                    return new DERBitString(out, out.length * 8 - pl);
                }
                int v = (ip[i] >> b) & 1;
                int bi = bitPos / 8;
                int off = 7 - (bitPos % 8);
                out[bi] |= (byte) (v << off);
                bitPos++;
            }
        }
        return new DERBitString(out, out.length * 8 - pl);
    }
}
