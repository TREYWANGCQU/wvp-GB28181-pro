// src/test/java/com/genersoft/iot/vmp/onvif/client/OnvifSecurityHeaderTest.java
package com.genersoft.iot.vmp.onvif.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ONVIF WS-Security 鉴权签名与时钟补偿测试")
public class OnvifSecurityHeaderTest {

    @Test
    @DisplayName("验证标准 PasswordDigest 生成算法")
    public void testPasswordDigestCalculation() throws Exception {
        // 固定测试向量 (RFC/OASIS WS-Security 规范样本)
        byte[] nonceRaw = "1234567890abcdef".getBytes(StandardCharsets.UTF_8);
        String created = "2026-09-08T09:00:00Z";
        String password = "adminPassword123";

        // 执行算法
        String digest = OnvifSecurityHeader.calculatePasswordDigest(nonceRaw, created, password);
        assertNotNull(digest);
        assertFalse(digest.isEmpty());

        // 独立推算期望结果验证算法确定性
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        sha1.update(nonceRaw);
        sha1.update(created.getBytes(StandardCharsets.UTF_8));
        sha1.update(password.getBytes(StandardCharsets.UTF_8));
        String expected = Base64.getEncoder().encodeToString(sha1.digest());

        assertEquals(expected, digest, "PasswordDigest 计算结果必须完全匹配");
    }

    @Test
    @DisplayName("验证动态时钟补偿偏移量注入")
    public void testClockOffsetCompensation() {
        String username = "admin";
        String password = "password";
        // 假设摄像头 RTC 比本地服务器快 3600000ms (1小时)
        long clockOffsetMillis = 3600_000L;

        String headerXml = OnvifSecurityHeader.buildHeader(username, password, clockOffsetMillis);
        assertNotNull(headerXml);
        assertTrue(headerXml.contains("<wsse:Username>admin</wsse:Username>"));
        assertTrue(headerXml.contains("wsu:Created"));

        // 提取生成的时间戳
        int startIdx = headerXml.indexOf("<wsu:Created>") + "<wsu:Created>".length();
        int endIdx = headerXml.indexOf("</wsu:Created>");
        String createdStr = headerXml.substring(startIdx, endIdx);

        Instant createdTime = Instant.parse(createdStr);
        Instant now = Instant.now();

        // 补偿后时间应当大约比当前本地时间大 1 小时 (容差 5 秒以内)
        long diffSeconds = createdTime.getEpochSecond() - now.getEpochSecond();
        assertTrue(diffSeconds >= 3595 && diffSeconds <= 3605, "生成的 Created 时间戳必须包含指定的时钟偏移量");
    }

    @Test
    @DisplayName("验证 WS-Security 签名生成与 PasswordDigest 算法正确性")
    public void testBuildHeaderAndDigest() throws Exception {
        String username = "admin";
        String password = "password123";
        long clockOffsetMillis = 0L;

        String headerXml = OnvifSecurityHeader.buildHeader(username, password, clockOffsetMillis);
        assertNotNull(headerXml);
        assertTrue(headerXml.contains("<wsse:Username>admin</wsse:Username>"));
        assertTrue(headerXml.contains("PasswordDigest"));

        // 提取 PasswordDigest, Nonce, Created 进行独立核验
        Pattern digestPattern = Pattern.compile("<wsse:Password[^>]*>([^<]+)</wsse:Password>");
        Pattern noncePattern = Pattern.compile("<wsse:Nonce[^>]*>([^<]+)</wsse:Nonce>");
        Pattern createdPattern = Pattern.compile("<wsu:Created>([^<]+)</wsu:Created>");

        Matcher digestMatcher = digestPattern.matcher(headerXml);
        Matcher nonceMatcher = noncePattern.matcher(headerXml);
        Matcher createdMatcher = createdPattern.matcher(headerXml);

        assertTrue(digestMatcher.find());
        assertTrue(nonceMatcher.find());
        assertTrue(createdMatcher.find());

        String digest = digestMatcher.group(1);
        String nonceBase64 = nonceMatcher.group(1);
        String created = createdMatcher.group(1);

        byte[] nonceRaw = Base64.getDecoder().decode(nonceBase64);
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        sha1.update(nonceRaw);
        sha1.update(created.getBytes(StandardCharsets.UTF_8));
        sha1.update(password.getBytes(StandardCharsets.UTF_8));
        String expectedDigest = Base64.getEncoder().encodeToString(sha1.digest());

        assertEquals(expectedDigest, digest);
    }

    @Test
    @DisplayName("验证时钟偏斜自愈功能 (模拟快2小时与慢1小时)")
    public void testClockSkewCompensation() {
        String username = "admin";
        String password = "password123";

        // 1. 模拟摄像头时钟比服务器快 2 小时
        long fastTwoHoursMillis = Duration.ofHours(2).toMillis();
        String headerFast = OnvifSecurityHeader.buildHeader(username, password, fastTwoHoursMillis);

        Pattern createdPattern = Pattern.compile("<wsu:Created>([^<]+)</wsu:Created>");
        Matcher matcherFast = createdPattern.matcher(headerFast);
        assertTrue(matcherFast.find());
        Instant fastCreated = Instant.parse(matcherFast.group(1));

        long diffFastSeconds = Math.abs(Duration.between(Instant.now().plusMillis(fastTwoHoursMillis), fastCreated).getSeconds());
        assertTrue(diffFastSeconds <= 2, "时间戳与预期的快2小时摄像头本地时间相差不应超过2秒");

        // 2. 模拟摄像头时钟比服务器慢 1 小时
        long slowOneHourMillis = -Duration.ofHours(1).toMillis();
        String headerSlow = OnvifSecurityHeader.buildHeader(username, password, slowOneHourMillis);
        Matcher matcherSlow = createdPattern.matcher(headerSlow);
        assertTrue(matcherSlow.find());
        Instant slowCreated = Instant.parse(matcherSlow.group(1));

        long diffSlowSeconds = Math.abs(Duration.between(Instant.now().plusMillis(slowOneHourMillis), slowCreated).getSeconds());
        assertTrue(diffSlowSeconds <= 2, "时间戳与预期的慢1小时摄像头本地时间相差不应超过2秒");
    }

    @Test
    @DisplayName("验证空用户名或密码时的降级返回")
    public void testEmptyUserOrPassword() {
        assertEquals("<s:Header/>", OnvifSecurityHeader.buildHeader(null, "pwd", 0));
        assertEquals("<s:Header/>", OnvifSecurityHeader.buildHeader("", "pwd", 0));
        assertEquals("<s:Header/>", OnvifSecurityHeader.buildHeader("admin", null, 0));
    }
}
