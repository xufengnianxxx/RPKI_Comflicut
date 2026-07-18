package com.rpki.conflictchecker.util;

import java.math.BigInteger;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * IPv6 CIDR 包含与重叠判断（128 位，{@link BigInteger}），供 RFC 3779 资源冲突检测使用。
 */
public final class Ipv6CidrUtils {

    private static final BigInteger ONES_128 = BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE);

    private Ipv6CidrUtils() {
    }

    /**
     * 仅保留标准 {@code addr/prefixLen} 形式（单段 CIDR），忽略解析器产生的 host-host 范围串。
     */
    public static List<String> normalizeIpv6Cidrs(List<String> values) {
        List<String> out = new ArrayList<>();
        if (values == null) {
            return out;
        }
        for (String v : values) {
            if (v == null) {
                continue;
            }
            String s = v.trim();
            if (s.isEmpty() || s.startsWith("RFC3779_") || s.contains("-")) {
                continue;
            }
            int slash = s.lastIndexOf('/');
            if (slash <= 0 || slash >= s.length() - 1) {
                continue;
            }
            String host = s.substring(0, slash);
            int plen;
            try {
                plen = Integer.parseInt(s.substring(slash + 1));
            } catch (NumberFormatException e) {
                continue;
            }
            if (plen < 0 || plen > 128) {
                continue;
            }
            try {
                InetAddress ia = InetAddress.getByName(host);
                if (!(ia instanceof Inet6Address)) {
                    continue;
                }
            } catch (Exception e) {
                continue;
            }
            out.add(s);
        }
        return out;
    }

    public static boolean isIpv6CidrContains(String parentCidr, String childCidr) {
        try {
            Cidr6 p = parse(parentCidr);
            Cidr6 c = parse(childCidr);
            if (p == null || c == null) {
                return false;
            }
            if (p.prefixLen > c.prefixLen) {
                return false;
            }
            BigInteger maskP = prefixMask(p.prefixLen);
            return p.network.and(maskP).equals(c.network.and(maskP));
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isIpv6CidrOverlapping(String aCidr, String bCidr) {
        try {
            Cidr6 a = parse(aCidr);
            Cidr6 b = parse(bCidr);
            if (a == null || b == null) {
                return false;
            }
            BigInteger aEnd = a.network.add(a.width()).subtract(BigInteger.ONE);
            BigInteger bEnd = b.network.add(b.width()).subtract(BigInteger.ONE);
            return a.network.compareTo(bEnd) <= 0 && b.network.compareTo(aEnd) <= 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 同长度前缀且地址空间在 IPv6 意义上紧挨（前一网络的 last+1 == 后一网络的 first），用于“相邻前缀”类告警。
     */
    public static boolean areAdjacentSamePrefixLen(String cidrA, String cidrB) {
        try {
            Cidr6 a = parse(cidrA);
            Cidr6 b = parse(cidrB);
            if (a == null || b == null || a.prefixLen != b.prefixLen) {
                return false;
            }
            BigInteger w = a.width();
            if (w.signum() <= 0) {
                return false;
            }
            BigInteger aLast = a.network.add(w).subtract(BigInteger.ONE);
            BigInteger bLast = b.network.add(w).subtract(BigInteger.ONE);
            return aLast.add(BigInteger.ONE).equals(b.network) || bLast.add(BigInteger.ONE).equals(a.network);
        } catch (Exception e) {
            return false;
        }
    }

    /** 高 {@code prefixLen} 位为 1 的 128 位网络掩码；plen=0 时为 0（不限制）。 */
    private static BigInteger prefixMask(int prefixLen) {
        if (prefixLen <= 0) {
            return BigInteger.ZERO;
        }
        if (prefixLen >= 128) {
            return ONES_128;
        }
        return BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE.shiftLeft(128 - prefixLen));
    }

    private static Cidr6 parse(String cidr) throws Exception {
        int slash = cidr.lastIndexOf('/');
        if (slash <= 0) {
            return null;
        }
        String host = cidr.substring(0, slash);
        int plen = Integer.parseInt(cidr.substring(slash + 1));
        InetAddress ia = InetAddress.getByName(host);
        if (!(ia instanceof Inet6Address)) {
            return null;
        }
        byte[] raw = ia.getAddress();
        BigInteger network = new BigInteger(1, raw);
        BigInteger mask = prefixMask(plen);
        network = network.and(mask);
        return new Cidr6(network, plen);
    }

    private record Cidr6(BigInteger network, int prefixLen) {
        BigInteger width() {
            int hostBits = 128 - prefixLen;
            if (hostBits <= 0) {
                return BigInteger.ONE;
            }
            return BigInteger.ONE.shiftLeft(hostBits);
        }
    }
}
