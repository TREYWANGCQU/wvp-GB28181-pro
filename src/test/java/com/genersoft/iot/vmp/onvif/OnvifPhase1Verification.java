// src/test/java/com/genersoft/iot/vmp/onvif/OnvifPhase1Verification.java
package com.genersoft.iot.vmp.onvif;

import com.genersoft.iot.vmp.onvif.bean.OnvifProbeResult;
import com.genersoft.iot.vmp.onvif.client.OnvifDiscoveryClient;
import com.genersoft.iot.vmp.onvif.client.OnvifSecurityHeader;
import com.genersoft.iot.vmp.onvif.client.OnvifXmlBuilder;
import com.genersoft.iot.vmp.onvif.client.OnvifXmlParser;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 阶段一验收标准快速自动化自检启动器
 */
public class OnvifPhase1Verification {

    public static void main(String[] args) {
        System.out.println("========== 开始执行 ONVIF 第一阶段自动化验收自检 ==========");
        int total = 0;
        int passed = 0;

        // 1. 测试 WS-Security UsernameToken 签名生成与 Digest 计算
        total++;
        try {
            String username = "admin";
            String password = "password123";
            String headerXml = OnvifSecurityHeader.buildHeader(username, password, 0);

            Pattern digestPattern = Pattern.compile("<wsse:Password[^>]*>([^<]+)</wsse:Password>");
            Pattern noncePattern = Pattern.compile("<wsse:Nonce[^>]*>([^<]+)</wsse:Nonce>");
            Pattern createdPattern = Pattern.compile("<wsu:Created>([^<]+)</wsu:Created>");

            Matcher digestMatcher = digestPattern.matcher(headerXml);
            Matcher nonceMatcher = noncePattern.matcher(headerXml);
            Matcher createdMatcher = createdPattern.matcher(headerXml);

            if (!digestMatcher.find() || !nonceMatcher.find() || !createdMatcher.find()) {
                throw new RuntimeException("XML 节点抽取失败");
            }

            String digest = digestMatcher.group(1);
            String nonceBase64 = nonceMatcher.group(1);
            String created = createdMatcher.group(1);

            byte[] nonceRaw = Base64.getDecoder().decode(nonceBase64);
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(nonceRaw);
            sha1.update(created.getBytes(StandardCharsets.UTF_8));
            sha1.update(password.getBytes(StandardCharsets.UTF_8));
            String expectedDigest = Base64.getEncoder().encodeToString(sha1.digest());

            if (!expectedDigest.equals(digest)) {
                throw new RuntimeException("Digest 计算不匹配: expected=" + expectedDigest + ", actual=" + digest);
            }
            System.out.println("[PASS] 1. WS-Security UsernameToken 签名与 PasswordDigest 计算正确");
            passed++;
        } catch (Exception e) {
            System.err.println("[FAIL] 1. WS-Security 签名测试失败: " + e.getMessage());
        }

        // 2. 测试时钟偏差自愈 (+2小时 / -1小时)
        total++;
        try {
            String username = "admin";
            String password = "password123";
            Pattern createdPattern = Pattern.compile("<wsu:Created>([^<]+)</wsu:Created>");

            // 偏快 2 小时
            long fast2h = Duration.ofHours(2).toMillis();
            String headerFast = OnvifSecurityHeader.buildHeader(username, password, fast2h);
            Matcher matcherFast = createdPattern.matcher(headerFast);
            if (!matcherFast.find()) throw new RuntimeException("未找到 Created 节点");
            Instant fastCreated = Instant.parse(matcherFast.group(1));
            long diffFast = Math.abs(Duration.between(Instant.now().plusMillis(fast2h), fastCreated).getSeconds());
            if (diffFast > 2) throw new RuntimeException("时钟补偿误差超过阈值: " + diffFast + "s");

            // 偏慢 1 小时
            long slow1h = -Duration.ofHours(1).toMillis();
            String headerSlow = OnvifSecurityHeader.buildHeader(username, password, slow1h);
            Matcher matcherSlow = createdPattern.matcher(headerSlow);
            if (!matcherSlow.find()) throw new RuntimeException("未找到 Created 节点");
            Instant slowCreated = Instant.parse(matcherSlow.group(1));
            long diffSlow = Math.abs(Duration.between(Instant.now().plusMillis(slow1h), slowCreated).getSeconds());
            if (diffSlow > 2) throw new RuntimeException("时钟补偿误差超过阈值: " + diffSlow + "s");

            System.out.println("[PASS] 2. 时钟偏差自愈机制有效 (快2小时/慢1小时误差均<=2s)");
            passed++;
        } catch (Exception e) {
            System.err.println("[FAIL] 2. 时钟偏差补偿测试失败: " + e.getMessage());
        }

        // 3. 测试 XML Builder 模板生成
        total++;
        try {
            String timeReq = OnvifXmlBuilder.buildGetSystemDateAndTime();
            String devReq = OnvifXmlBuilder.buildGetDeviceInformation("<s:Header/>");
            String ptzReq = OnvifXmlBuilder.buildContinuousMove(null, "profile_1", 0.5, -0.5, 1.0);

            if (!timeReq.contains("<tds:GetSystemDateAndTime/>") ||
                !devReq.contains("<tds:GetDeviceInformation/>") ||
                !ptzReq.contains("x=\"0.500\" y=\"-0.500\"")) {
                throw new RuntimeException("XML 模板构造内容不匹配");
            }
            System.out.println("[PASS] 3. OnvifXmlBuilder 预编译模板封装与参数插值正确");
            passed++;
        } catch (Exception e) {
            System.err.println("[FAIL] 3. OnvifXmlBuilder 测试失败: " + e.getMessage());
        }

        // 4. 测试 dom4j 宽容型解析器
        total++;
        try {
            String sampleTimeXml =
                    "<SOAP-ENV:Envelope xmlns:SOAP-ENV=\"http://www.w3.org/2003/05/soap-envelope\">\n" +
                    "  <SOAP-ENV:Body>\n" +
                    "    <GetSystemDateAndTimeResponse xmlns=\"http://www.onvif.org/ver10/device/wsdl\">\n" +
                    "      <SystemDateAndTime><UTCDateTime>\n" +
                    "        <Time><Hour>14</Hour><Minute>20</Minute><Second>30</Second></Time>\n" +
                    "        <Date><Year>2026</Year><Month>9</Month><Day>8</Day></Date>\n" +
                    "      </UTCDateTime></SystemDateAndTime>\n" +
                    "    </GetSystemDateAndTimeResponse>\n" +
                    "  </SOAP-ENV:Body>\n" +
                    "</SOAP-ENV:Envelope>";
            Instant parsedTime = OnvifXmlParser.parseSystemDateTime(sampleTimeXml);
            if (!parsedTime.toString().equals("2026-09-08T14:20:30Z")) {
                throw new RuntimeException("UTC 时间解析错误: " + parsedTime);
            }

            String sampleDevInfo =
                    "<Envelope><Body><GetDeviceInformationResponse xmlns=\"http://www.onvif.org/ver10/device/wsdl\">" +
                    "<Manufacturer>Hikvision</Manufacturer><Model>DS-2CD2047G2-LU</Model>" +
                    "<FirmwareVersion>V5.7.1</FirmwareVersion><SerialNumber>AAWR123456</SerialNumber>" +
                    "</GetDeviceInformationResponse></Body></Envelope>";
            Map<String, String> devInfo = OnvifXmlParser.parseDeviceInformation(sampleDevInfo);
            if (!"Hikvision".equals(devInfo.get("manufacturer")) || !"DS-2CD2047G2-LU".equals(devInfo.get("model"))) {
                throw new RuntimeException("设备硬件信息解析错误: " + devInfo);
            }

            System.out.println("[PASS] 4. OnvifXmlParser 宽容解析 UTC时间 与 设备硬件信息成功");
            passed++;
        } catch (Exception e) {
            System.err.println("[FAIL] 4. OnvifXmlParser 测试失败: " + e.getMessage());
        }

        // 5. 测试 WS-Discovery 探测响应报文解析
        total++;
        try {
            OnvifDiscoveryClient discoveryClient = new OnvifDiscoveryClient();
            String probeMatchXml =
                    "<Envelope xmlns:dn=\"http://www.onvif.org/ver10/network/wsdl\"\n" +
                    "          xmlns=\"http://www.w3.org/2003/05/soap-envelope\"\n" +
                    "          xmlns:wsa=\"http://schemas.xmlsoap.org/ws/2004/08/addressing\">\n" +
                    "  <Body>\n" +
                    "    <ProbeMatches xmlns=\"http://schemas.xmlsoap.org/ws/2005/04/discovery\">\n" +
                    "      <ProbeMatch>\n" +
                    "        <wsa:EndpointReference><wsa:Address>urn:uuid:test-uuid-1234</wsa:Address></wsa:EndpointReference>\n" +
                    "        <Types>dn:NetworkVideoTransmitter</Types>\n" +
                    "        <Scopes>onvif://www.onvif.org/hardware/DS-2CD2047G2-LU onvif://www.onvif.org/name/FrontDoorCamera onvif://www.onvif.org/manufacturer/Hikvision</Scopes>\n" +
                    "        <XAddrs>http://192.168.1.108:80/onvif/device_service</XAddrs>\n" +
                    "      </ProbeMatch>\n" +
                    "    </ProbeMatches>\n" +
                    "  </Body>\n" +
                    "</Envelope>";
            Map<String, OnvifProbeResult> map = new HashMap<>();
            discoveryClient.parseProbeMatch(probeMatchXml, "192.168.1.108", map);

            OnvifProbeResult res = map.get("192.168.1.108:80");
            if (res == null || !"Hikvision".equals(res.getManufacturer()) || !"DS-2CD2047G2-LU".equals(res.getModel()) || !"FrontDoorCamera".equals(res.getName())) {
                throw new RuntimeException("ProbeMatches 解析结果错误: " + res);
            }
            System.out.println("[PASS] 5. WS-Discovery ProbeMatches 报文解析与元数据抽取准确");
            passed++;
        } catch (Exception e) {
            System.err.println("[FAIL] 5. WS-Discovery 解析测试失败: " + e.getMessage());
        }

        System.out.println(String.format("========== 阶段一自检完成: 共 %d 项，通过 %d 项，失败 %d 项 ==========",
                total, passed, total - passed));

        if (total != passed) {
            System.exit(1);
        }
    }
}
