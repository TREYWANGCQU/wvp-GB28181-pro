package com.genersoft.iot.vmp.utils;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

public class IpPortUtil {

    /**
     * 拼接IP和端口
     * @param ip IP地址字符串
     * @param port 端口号字符串
     * @return 拼接后的字符串
     * @throws IllegalArgumentException 如果IP地址无效或端口无效
     */
    public static String concatenateIpAndPort(String ip, String port) {
        if (port == null || port.isEmpty()) {
            throw new IllegalArgumentException("端口号不能为空");
        }

        // 验证端口是否为有效数字
        try {
            int portNum = Integer.parseInt(port);
            if (portNum < 0 || portNum > 65535) {
                throw new IllegalArgumentException("端口号必须在0-65535范围内");
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("端口号必须是有效数字", e);
        }

        try {
            InetAddress inetAddress = InetAddress.getByName(ip);

            if (inetAddress instanceof Inet6Address) {
                // IPv6地址需要加上方括号
                return "[" + ip + "]:" + port;
            } else {
                // IPv4地址直接拼接
                return ip + ":" + port;
            }
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("无效的IP地址: " + ip, e);
        }
    }

    /**
     * 判断IP是否属于RFC 1918私网地址或本地保留地址
     * @param ip IP地址
     * @return 是否为私网地址
     */
    public static boolean isPrivateAddress(String ip) {
        if (ip == null || ip.trim().isEmpty()) {
            return false;
        }
        try {
            InetAddress address = InetAddress.getByName(ip.trim());
            if (address.isSiteLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()) {
                return true;
            }
            byte[] bytes = address.getAddress();
            if (bytes.length == 4) {
                int b0 = bytes[0] & 0xFF;
                int b1 = bytes[1] & 0xFF;
                // 10.0.0.0/8
                if (b0 == 10) return true;
                // 172.16.0.0/12 (172.16.0.0 - 172.31.255.255)
                if (b0 == 172 && (b1 >= 16 && b1 <= 31)) return true;
                // 192.168.0.0/16
                if (b0 == 192 && b1 == 168) return true;
                // 127.0.0.0/8
                if (b0 == 127) return true;
                // 169.254.0.0/16
                if (b0 == 169 && b1 == 254) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }
}
