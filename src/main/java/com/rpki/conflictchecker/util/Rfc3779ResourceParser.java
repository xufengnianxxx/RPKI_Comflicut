package com.rpki.conflictchecker.util;

import org.bouncycastle.asn1.*;

import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * RFC 3779 资源扩展解析：id-pe-ipAddrBlocks (1.3.6.1.5.5.7.1.7)、id-pe-autonomousSysNums (1.3.6.1.5.5.7.1.8)。
 */
public final class Rfc3779ResourceParser {

    public static final ASN1ObjectIdentifier OID_IP_ADDR_BLOCKS = new ASN1ObjectIdentifier("1.3.6.1.5.5.7.1.7");
    public static final ASN1ObjectIdentifier OID_AS_IDS = new ASN1ObjectIdentifier("1.3.6.1.5.5.7.1.8");

    private Rfc3779ResourceParser() {
    }

    public static void parseIpResources(ASN1Encodable extValue, List<String> ipv4, List<String> ipv6) throws IOException {
        if (extValue == null) {
            return;
        }
        ASN1Primitive prim = extValue.toASN1Primitive();
        if (prim instanceof ASN1OctetString) {
            prim = ASN1Primitive.fromByteArray(((ASN1OctetString) prim).getOctets());
        }
        ASN1Sequence families = ASN1Sequence.getInstance(prim);
        for (int i = 0; i < families.size(); i++) {
            ASN1Sequence family = ASN1Sequence.getInstance(families.getObjectAt(i));
            if (family.size() < 2) {
                continue;
            }
            byte[] af = ASN1OctetString.getInstance(family.getObjectAt(0)).getOctets();
            boolean v4 = af.length >= 2 && af[0] == 0 && af[1] == 1;
            boolean v6 = af.length >= 2 && af[0] == 0 && af[1] == 2;
            if (!v4 && !v6) {
                continue;
            }
            ASN1Encodable choice = family.getObjectAt(1);
            if (choice instanceof ASN1Null || DERNull.INSTANCE.equals(choice)) {
                continue;
            }
            ASN1Sequence addrs;
            if (choice instanceof ASN1TaggedObject) {
                addrs = ASN1Sequence.getInstance((ASN1TaggedObject) choice, false);
            } else {
                addrs = ASN1Sequence.getInstance(choice);
            }
            if (addrs == null) {
                continue;
            }
            for (int j = 0; j < addrs.size(); j++) {
                ASN1Encodable or = addrs.getObjectAt(j);
                if (or instanceof ASN1BitString) {
                    addIpPrefixChoice(v4, ASN1BitString.getInstance(or), ipv4, ipv6);
                } else if (or instanceof ASN1Sequence seq) {
                    // RFC 3779 IPAddressOrRange ::= CHOICE { addressPrefix IPAddress, addressRange IPAddressRange }
                    // IPAddress ::= SEQUENCE { address BIT STRING, maxLength INTEGER OPTIONAL }
                    // IPAddressRange ::= SEQUENCE { min BIT STRING, max BIT STRING }
                    if (seq.size() == 2
                            && seq.getObjectAt(0) instanceof ASN1BitString
                            && seq.getObjectAt(1) instanceof ASN1BitString) {
                        ASN1BitString minBs = ASN1BitString.getInstance(seq.getObjectAt(0));
                        ASN1BitString maxBs = ASN1BitString.getInstance(seq.getObjectAt(1));
                        if (v4) {
                            ipv4RangeToCidrs(minBs, maxBs, ipv4);
                        } else {
                            ipv6RangeToCidrs(minBs, maxBs, ipv6);
                        }
                    } else if (seq.size() >= 1 && seq.getObjectAt(0) instanceof ASN1BitString addrBs) {
                        // SEQUENCE { address BIT STRING, maxLength INTEGER OPTIONAL }：CIDR 仅由 BIT STRING 推导（RFC 6487）
                        addIpPrefixChoice(v4, addrBs, ipv4, ipv6);
                    }
                }
            }
        }
    }

    public static List<String> parseAsResources(ASN1Encodable extValue) throws IOException {
        List<String> out = new ArrayList<>();
        if (extValue == null) {
            return out;
        }
        ASN1Primitive prim = extValue.toASN1Primitive();
        if (prim instanceof ASN1OctetString) {
            prim = ASN1Primitive.fromByteArray(((ASN1OctetString) prim).getOctets());
        }
        ASN1Sequence outer = ASN1Sequence.getInstance(prim);
        for (int i = 0; i < outer.size(); i++) {
            ASN1Encodable rawEl = outer.getObjectAt(i);
            if (!(rawEl instanceof ASN1TaggedObject)) {
                continue;
            }
            ASN1TaggedObject tag = (ASN1TaggedObject) rawEl;
            int tagNo = tag.getTagNo();
            if (tagNo != 0 && tagNo != 1) {
                continue;
            }
            // AsNumbers = SEQUENCE OF ASIdOrRange (INTEGER 或 range = SEQUENCE( min, max )).
            ASN1Sequence idOrRanges = ASN1Sequence.getInstance(tag, false);
            if (idOrRanges == null || idOrRanges.size() < 1) {
                continue;
            }
            for (int j = 0; j < idOrRanges.size(); j++) {
                ASN1Encodable el = idOrRanges.getObjectAt(j);
                if (el instanceof ASN1Integer) {
                    BigInteger id = ASN1Integer.getInstance(el).getPositiveValue();
                    out.add(id.toString());
                } else if (el instanceof ASN1Sequence) {
                    ASN1Sequence r = (ASN1Sequence) el;
                    if (r.size() == 1 && r.getObjectAt(0) instanceof ASN1Integer) {
                        BigInteger id = ASN1Integer.getInstance(r.getObjectAt(0)).getPositiveValue();
                        out.add(id.toString());
                    } else if (r.size() == 2) {
                        BigInteger min = ASN1Integer.getInstance(r.getObjectAt(0)).getPositiveValue();
                        BigInteger max = ASN1Integer.getInstance(r.getObjectAt(1)).getPositiveValue();
                        if (min.compareTo(max) > 0) {
                            continue;
                        }
                        expandAsRange(min, max, out);
                    }
                }
            }
        }
        return out;
    }

    private static void expandAsRange(BigInteger min, BigInteger max, List<String> out) {
        BigInteger span = max.subtract(min);
        if (span.compareTo(BigInteger.valueOf(50_000)) > 0) {
            out.add(min.toString() + "-" + max.toString());
            return;
        }
        BigInteger cur = min;
        while (cur.compareTo(max) <= 0) {
            out.add(cur.toString());
            cur = cur.add(BigInteger.ONE);
        }
    }

    /**
     * 将 {@code IPAddressOrRange} 中的 addressPrefix 规范为 CIDR：既支持裸 {@link ASN1BitString}，
     * 也支持 RFC 3779 {@code SEQUENCE { address BIT STRING, maxLength INTEGER OPTIONAL }}（与 BC 中该结构的 BER/DER 等价）。
     * 入库前缀仅以 BIT STRING 的有效位长度为 CIDR 掩码；{@code maxLength} 不改变 addr/prefix 网络边界（见 RFC 6487）。
     */
    private static void addIpPrefixChoice(boolean v4, ASN1BitString bs, List<String> ipv4, List<String> ipv6) {
        if (v4) {
            String cidr = ipv4PrefixToCidr(bs);
            if (cidr != null) {
                ipv4.add(cidr);
            }
        } else {
            String cidr = ipv6PrefixToCidr(bs);
            if (cidr != null) {
                ipv6.add(cidr);
            }
        }
    }

    private static String ipv4PrefixToCidr(ASN1BitString bs) {
        int pad = bs.getPadBits();
        byte[] octets = bs.getOctets();
        int sigBits = octets.length * 8 - pad;
        if (sigBits < 0 || sigBits > 32) {
            return null;
        }
        long addr = readBitsAsLeftAlignedIpv4(octets, pad, sigBits);
        return IPAddressUtils.longToIp(addr) + "/" + sigBits;
    }

    private static String ipv6PrefixToCidr(ASN1BitString bs) {
        int pad = bs.getPadBits();
        byte[] octets = bs.getOctets();
        int sigBits = octets.length * 8 - pad;
        if (sigBits < 0 || sigBits > 128) {
            return null;
        }
        byte[] addr = readBitsAsLeftAlignedIpv6(octets, pad, sigBits);
        try {
            return InetAddress.getByAddress(addr).getHostAddress() + "/" + sigBits;
        } catch (Exception e) {
            return null;
        }
    }

    private static long readBitsAsLeftAlignedIpv4(byte[] octets, int padBits, int sigBits) {
        long v = 0;
        int taken = 0;
        outer:
        for (byte b : octets) {
            for (int bit = 7; bit >= 0; bit--) {
                if (taken >= sigBits) {
                    break outer;
                }
                v = (v << 1) | ((b >> bit) & 1);
                taken++;
            }
        }
        return (sigBits == 0) ? 0 : (v << (32 - sigBits));
    }

    private static byte[] readBitsAsLeftAlignedIpv6(byte[] octets, int padBits, int sigBits) {
        byte[] addr = new byte[16];
        int taken = 0;
        int byteIdx = 0;
        int bitInByte = 7;
        outer:
        for (byte b : octets) {
            for (int bit = 7; bit >= 0; bit--) {
                if (taken >= sigBits) {
                    break outer;
                }
                int v = (b >> bit) & 1;
                addr[byteIdx] |= (byte) (v << bitInByte);
                bitInByte--;
                if (bitInByte < 0) {
                    byteIdx++;
                    bitInByte = 7;
                }
                taken++;
            }
        }
        return addr;
    }

    private static void ipv4RangeToCidrs(ASN1BitString minBs, ASN1BitString maxBs, List<String> out) {
        long min = readIpv4Endpoint(minBs);
        long max = readIpv4Endpoint(maxBs);
        if (min < 0 || max < 0 || min > max) {
            return;
        }
        decomposeIpv4Range(min, max, out);
    }

    /**
     * 将 min/max 解释为 32 位主机地址（BIT STRING 有效位为 32）。
     */
    private static long readIpv4Endpoint(ASN1BitString bs) {
        int pad = bs.getPadBits();
        byte[] octets = bs.getOctets();
        int sigBits = octets.length * 8 - pad;
        if (sigBits != 32) {
            return -1L;
        }
        long v = 0;
        int taken = 0;
        for (byte b : octets) {
            for (int bit = 7; bit >= 0 && taken < 32; bit--) {
                v = (v << 1) | ((b >> bit) & 1);
                taken++;
            }
        }
        return v & 0xffffffffL;
    }

    private static void decomposeIpv4Range(long first, long last, List<String> out) {
        while (Long.compareUnsigned(first, last) <= 0) {
            int pl = 32;
            boolean placed = false;
            while (pl >= 0) {
                long block = ipv4BlockSize(pl);
                if (pl > 0 && Long.remainderUnsigned(first, block) != 0) {
                    pl--;
                    continue;
                }
                long end = first + block - 1;
                if (Long.compareUnsigned(end, last) > 0) {
                    pl--;
                    continue;
                }
                if (pl == 0) {
                    out.add("0.0.0.0/0");
                    return;
                }
                out.add(IPAddressUtils.longToIp(first) + "/" + pl);
                first = first + block;
                placed = true;
                break;
            }
            if (!placed) {
                break;
            }
        }
    }

    private static long ipv4BlockSize(int pl) {
        if (pl == 32) {
            return 1L;
        }
        if (pl == 0) {
            return 0x1_0000_0000L;
        }
        if (pl < 0 || pl > 32) {
            return 0L;
        }
        return Integer.toUnsignedLong(1 << (32 - pl));
    }

    private static void ipv6RangeToCidrs(ASN1BitString minBs, ASN1BitString maxBs, List<String> ipv6) {
        try {
            int pMin = minBs.getPadBits();
            int pMax = maxBs.getPadBits();
            byte[] oMin = minBs.getOctets();
            byte[] oMax = maxBs.getOctets();
            int sMin = oMin.length * 8 - pMin;
            int sMax = oMax.length * 8 - pMax;
            if (sMin != 128 || sMax != 128) {
                return;
            }
            byte[] bMin = readBitsAsLeftAlignedIpv6(oMin, pMin, 128);
            byte[] bMax = readBitsAsLeftAlignedIpv6(oMax, pMax, 128);
            BigInteger min = new BigInteger(1, bMin);
            BigInteger max = new BigInteger(1, bMax);
            if (min.compareTo(max) > 0) {
                return;
            }
            ipv6.add(InetAddress.getByAddress(bMin).getHostAddress() + "-" + InetAddress.getByAddress(bMax).getHostAddress());
        } catch (Exception ignored) {
            // 大范围 IPv6 分解复杂，仅保留端点文本
        }
    }
}
