package com.rpki.conflictchecker.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class IPAddressUtils {
    
    private static final Pattern IPV4_PATTERN = Pattern.compile(
            "^(([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])\\.){3}([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])(/([0-9]|[1-2][0-9]|3[0-2]))?$"
    );
    
    private static final Pattern IPV6_PATTERN = Pattern.compile(
            "(([0-9a-fA-F]{1,4}:){7,7}[0-9a-fA-F]{1,4}|" +
            "([0-9a-fA-F]{1,4}:){1,7}:|" +
            "([0-9a-fA-F]{1,4}:){1,6}:[0-9a-fA-F]{1,4}|" +
            "([0-9a-fA-F]{1,4}:){1,5}(:[0-9a-fA-F]{1,4}){1,2}|" +
            "([0-9a-fA-F]{1,4}:){1,4}(:[0-9a-fA-F]{1,4}){1,3}|" +
            "([0-9a-fA-F]{1,4}:){1,3}(:[0-9a-fA-F]{1,4}){1,4}|" +
            "([0-9a-fA-F]{1,4}:){1,2}(:[0-9a-fA-F]{1,4}){1,5}|" +
            "[0-9a-fA-F]{1,4}:((:[0-9a-fA-F]{1,4}){1,6})|" +
            ":((:[0-9a-fA-F]{1,4}){1,7}|:)|" +
            "fe80:(:[0-9a-fA-F]{0,4}){0,4}%[0-9a-zA-Z]{1,}|" +
            "::(ffff(:0{1,4}){0,1}:){0,1}" +
            "((25[0-5]|(2[0-4]|1{0,1}[0-9]){0,1}[0-9])\\.){3,3}" +
            "(25[0-5]|(2[0-4]|1{0,1}[0-9]){0,1}[0-9])|" +
            "([0-9a-fA-F]{1,4}:){1,4}:" +
            "((25[0-5]|(2[0-4]|1{0,1}[0-9]){0,1}[0-9])\\.){3,3}" +
            "(25[0-5]|(2[0-4]|1{0,1}[0-9]){0,1}[0-9]))" +
            "(/([0-9]|[1-9][0-9]|1[0-1][0-9]|12[0-8]))?"
    );
    
    /**
     * 验证 IPv4 CIDR 地址
     */
    public static boolean isValidIPv4Cidr(String cidrAddress) {
        return IPV4_PATTERN.matcher(cidrAddress).matches();
    }
    
    /**
     * 验证 IPv6 CIDR 地址
     */
    public static boolean isValidIPv6Cidr(String cidrAddress) {
        return IPV6_PATTERN.matcher(cidrAddress).matches();
    }
    
    /**
     * 检测两个 IPv4 CIDR 是否重叠
     */
    public static boolean isIPv4CidrOverlapping(String cidr1, String cidr2) {
        try {
            String[] parts1 = cidr1.split("/");
            String[] parts2 = cidr2.split("/");
            
            if (parts1.length != 2 || parts2.length != 2) {
                return false;
            }
            
            String ip1 = parts1[0];
            String ip2 = parts2[0];
            int prefix1 = Integer.parseInt(parts1[1]);
            int prefix2 = Integer.parseInt(parts2[1]);
            
            long mask1 = getMask(prefix1);
            long mask2 = getMask(prefix2);
            
            long ipLong1 = ipToLong(ip1);
            long ipLong2 = ipToLong(ip2);
            
            long network1 = ipLong1 & mask1;
            long network2 = ipLong2 & mask2;
            
            // 检查网络地址是否重叠
            return (network1 & mask2) == network2 || (network2 & mask1) == network1;
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * IPv4 地址转长整数
     */
    public static long ipToLong(String ipAddress) {
        String[] parts = ipAddress.split("\\.");
        return (Long.parseLong(parts[0]) << 24) +
               (Long.parseLong(parts[1]) << 16) +
               (Long.parseLong(parts[2]) << 8) +
               Long.parseLong(parts[3]);
    }
    
    /**
     * 获取掩码
     */
    private static long getMask(int prefixLength) {
        if (prefixLength == 0) {
            return 0;
        }
        return (-1L) << (32 - prefixLength);
    }
    
    /**
     * 长整数转 IPv4 地址
     */
    public static String longToIp(long ipLong) {
        return ((ipLong >> 24) & 0xFF) + "." +
               ((ipLong >> 16) & 0xFF) + "." +
               ((ipLong >> 8) & 0xFF) + "." +
               (ipLong & 0xFF);
    }
    
    /**
     * 检测 IP 包含关系（CIDR1 是否包含 CIDR2）
     */
    /**
     * 同掩码长度且两个网段在数值上紧挨（前一个的广播地址 +1 等于后一个的网络地址），用于相邻前缀边界告警。
     */
    public static boolean areAdjacentIpv4SamePrefixLen(String cidrA, String cidrB) {
        try {
            String[] pa = cidrA.split("/");
            String[] pb = cidrB.split("/");
            if (pa.length != 2 || pb.length != 2) {
                return false;
            }
            int lenA = Integer.parseInt(pa[1]);
            int lenB = Integer.parseInt(pb[1]);
            if (lenA != lenB) {
                return false;
            }
            long baseA = ipToLong(pa[0]) & getMask(lenA);
            long baseB = ipToLong(pb[0]) & getMask(lenB);
            long block = lenA == 0 ? 0x1_0000_0000L : Integer.toUnsignedLong(1 << (32 - lenA));
            if (block <= 0 || block > 0x1_0000_0000L) {
                return false;
            }
            long lastA = baseA + block - 1;
            long lastB = baseB + block - 1;
            return lastA + 1 == baseB || lastB + 1 == baseA;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isIPv4CidrContains(String cidr1, String cidr2) {
        try {
            String[] parts1 = cidr1.split("/");
            String[] parts2 = cidr2.split("/");
            
            if (parts1.length != 2 || parts2.length != 2) {
                return false;
            }
            
            int prefix1 = Integer.parseInt(parts1[1]);
            int prefix2 = Integer.parseInt(parts2[1]);
            
            // 包含，则前缀长度较小的网络包含前缀长度较大的网络
            if (prefix1 > prefix2) {
                return false;
            }
            
            long mask1 = getMask(prefix1);
            long ipLong1 = ipToLong(parts1[0]);
            long ipLong2 = ipToLong(parts2[0]);
            
            long network1 = ipLong1 & mask1;
            long network2 = ipLong2 & mask1;
            
            return network1 == network2;
        } catch (Exception e) {
            return false;
        }
    }
}
