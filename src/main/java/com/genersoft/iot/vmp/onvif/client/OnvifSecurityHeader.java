// src/main/java/com/genersoft/iot/vmp/onvif/client/OnvifSecurityHeader.java
package com.genersoft.iot.vmp.onvif.client;

import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

/**
 * WS-Security UsernameToken 签名生成器 (集成时钟偏差自动补偿)
 */
@Slf4j
public class OnvifSecurityHeader {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /**
     * 构建包含 WS-Security 的 SOAP Header 节点片段
     *
     * @param username          ONVIF 用户名
     * @param password          ONVIF 密码
     * @param clockOffsetMillis 本地与摄像机的时钟偏差(毫秒): CameraTime - LocalTime
     * @return SOAP Header XML 节点字符串
     */
    public static String buildHeader(String username, String password, long clockOffsetMillis) {
        if (username == null || username.trim().isEmpty() || password == null) {
            return "<s:Header/>";
        }

        // 1. 生成 16 字节真随机数
        byte[] nonceRaw = new byte[16];
        SECURE_RANDOM.nextBytes(nonceRaw);
        String nonceBase64 = Base64.getEncoder().encodeToString(nonceRaw);

        // 2. 计算补偿后的 UTC 时间戳
        Instant cameraAdjustedTime = Instant.now().plusMillis(clockOffsetMillis).truncatedTo(ChronoUnit.SECONDS);
        String created = cameraAdjustedTime.toString();

        // 3. 计算 PasswordDigest
        String passwordDigest = calculatePasswordDigest(nonceRaw, created, password);

        // 4. 组装 WS-Security XML
        return String.format(
            "<s:Header>\n" +
            "  <wsse:Security xmlns:wsse=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd\" " +
            "xmlns:wsu=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd\">\n" +
            "    <wsse:UsernameToken>\n" +
            "      <wsse:Username>%s</wsse:Username>\n" +
            "      <wsse:Password Type=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest\">%s</wsse:Password>\n" +
            "      <wsse:Nonce EncodingType=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary\">%s</wsse:Nonce>\n" +
            "      <wsu:Created>%s</wsu:Created>\n" +
            "    </wsse:UsernameToken>\n" +
            "  </wsse:Security>\n" +
            "</s:Header>",
            username, passwordDigest, nonceBase64, created
        );
    }

    /**
     * PasswordDigest = Base64(SHA-1(NonceRaw + CreatedUtf8 + PasswordUtf8))
     */
    public static String calculatePasswordDigest(byte[] nonceRaw, String created, String password) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(nonceRaw);
            sha1.update(created.getBytes(StandardCharsets.UTF_8));
            sha1.update(password.getBytes(StandardCharsets.UTF_8));
            byte[] digest = sha1.digest();
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            log.error("[ONVIF-Security] SHA-1 算法不可用", e);
            throw new RuntimeException("SHA-1 algorithm not available", e);
        }
    }
}
