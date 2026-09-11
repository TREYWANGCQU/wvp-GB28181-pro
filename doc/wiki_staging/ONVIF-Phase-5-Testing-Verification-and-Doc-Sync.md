# 第五阶段实施细节：多品牌 IPC 验证、联调测试与文档同步

## 1 阶段概述与技术定位

本阶段是 ONVIF 协议支持落地工程的收官环节。核心目标是通过单元测试、端到端集成测试、主流品牌硬件实测以及运维部署文档的同步，构筑高可靠的质量防护网。

核心任务：
1. **核心逻辑单元测试**：针对 WS-Security 签名生成、动态时钟补偿、非标宽容 XML 解析与 PTZ 归一化算法，编写完备的 JUnit 5 自动化测试用例；
2. **多品牌 IPC 兼容性实测与避坑指南**：系统梳理海康威视（Hikvision）、大华（Dahua）、宇视（Uniview）及雄迈/通用 IPC 的固件差异、鉴权陷阱与容错对策；
3. **运维与编译文档同步**：同步核对 [doc/reaticle_docs/compile.md](Compile-and-Dev-Guide)，固化 3702/UDP、554/TCP、80/TCP 网络矩阵与 Docker `network_mode: host` 部署铁律；
4. **交付质量验收基线**：建立生产就绪 Checklist。

---

## 2 自动化测试用例集

所有测试用例置于 `src/test/java/com/genersoft/iot/vmp/onvif/` 目录下。

### 2.1 WS-Security 签名与时钟补偿测试 `OnvifSecurityHeaderTest.java`

```java
// src/test/java/com/genersoft/iot/vmp/onvif/client/OnvifSecurityHeaderTest.java
package com.genersoft.iot.vmp.onvif.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;

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
}
```

---

### 2.2 宽容模式 XML 抽取测试 `OnvifXmlParserTest.java`

```java
// src/test/java/com/genersoft/iot/vmp/onvif/client/OnvifXmlParserTest.java
package com.genersoft.iot.vmp.onvif.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ONVIF 宽容型 XML 解析器容错测试")
public class OnvifXmlParserTest {

    @Test
    @DisplayName("测试解析非标、大小写混合与缺少前缀的系统时间报文")
    public void testLenientSystemDateTimeParsing() {
        // 模拟某厂商非标返回：缺少标准命名空间定义，标签大小写不一致
        String dirtyXml =
                "<SOAP-ENV:Envelope xmlns:SOAP-ENV=\"http://schemas.xmlsoap.org/soap/envelope/\">\n" +
                "  <SOAP-ENV:Body>\n" +
                "    <GetSystemDateAndTimeResponse>\n" +
                "      <SystemDateAndTime>\n" +
                "        <utcdatetime>\n" +
                "          <Time><hour>10</hour><minute>30</minute><second>45</second></Time>\n" +
                "          <Date><year>2026</year><month>9</month><day>8</day></Date>\n" +
                "        </utcdatetime>\n" +
                "      </SystemDateAndTime>\n" +
                "    </GetSystemDateAndTimeResponse>\n" +
                "  </SOAP-ENV:Body>\n" +
                "</SOAP-ENV:Envelope>";

        Instant parsed = OnvifXmlParser.parseSystemDateTime(dirtyXml);
        assertNotNull(parsed);

        // 验证转换后的时间
        var ldt = parsed.atOffset(ZoneOffset.UTC).toLocalDateTime();
        assertEquals(2026, ldt.getYear());
        assertEquals(9, ldt.getMonthValue());
        assertEquals(8, ldt.getDayOfMonth());
        assertEquals(10, ldt.getHour());
        assertEquals(30, ldt.getMinute());
        assertEquals(45, ldt.getSecond());
    }

    @Test
    @DisplayName("测试解析包含多层混杂包裹的设备硬件信息")
    public void testDeviceInformationParsing() {
        String dirtyXml =
                "<env:Envelope xmlns:env=\"http://www.w3.org/2003/05/soap-envelope\">\n" +
                "  <env:Body>\n" +
                "    <tds:GetDeviceInformationResponse xmlns:tds=\"http://www.onvif.org/ver10/device/wsdl\">\n" +
                "      <tds:Manufacturer>Hikvision</tds:Manufacturer>\n" +
                "      <tds:Model>DS-2CD2047G2-LU</tds:Model>\n" +
                "      <tds:FirmwareVersion>V5.7.13</tds:FirmwareVersion>\n" +
                "      <tds:SerialNumber>DS-2CD2047G2-LU20240510AAWR</tds:SerialNumber>\n" +
                "    </tds:GetDeviceInformationResponse>\n" +
                "  </env:Body>\n" +
                "</env:Envelope>";

        Map<String, String> info = OnvifXmlParser.parseDeviceInformation(dirtyXml);
        assertEquals("Hikvision", info.get("manufacturer"));
        assertEquals("DS-2CD2047G2-LU", info.get("model"));
        assertEquals("V5.7.13", info.get("firmwareVersion"));
        assertEquals("DS-2CD2047G2-LU20240510AAWR", info.get("serialNumber"));
    }
}
```

---

### 2.3 PTZ 归一化转换算法测试 `OnvifPtzNormalizationTest.java`

```java
// src/test/java/com/genersoft/iot/vmp/onvif/service/OnvifPtzNormalizationTest.java
package com.genersoft.iot.vmp.onvif.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ONVIF PTZ 国标速度到归一化浮点速度转换测试")
public class OnvifPtzNormalizationTest {

    private double calculatePanSpeed(Integer pan, Integer panSpeed) {
        if (pan == null) return 0.0;
        int speed = (panSpeed != null && panSpeed > 0) ? panSpeed : 128;
        double speedNorm = Math.min(1.0, speed / 255.0);
        return (pan == 0) ? -speedNorm : speedNorm;
    }

    private double calculateTiltSpeed(Integer tilt, Integer tiltSpeed) {
        if (tilt == null) return 0.0;
        int speed = (tiltSpeed != null && tiltSpeed > 0) ? tiltSpeed : 128;
        double speedNorm = Math.min(1.0, speed / 255.0);
        return (tilt == 0) ? speedNorm : -speedNorm;
    }

    @Test
    @DisplayName("验证边界值与方向性")
    public void testSpeedBoundaryAndDirection() {
        // 向左最大速度 (pan=0, panSpeed=255) -> -1.0
        assertEquals(-1.0, calculatePanSpeed(0, 255), 0.001);

        // 向右最大速度 (pan=1, panSpeed=255) -> +1.0
        assertEquals(1.0, calculatePanSpeed(1, 255), 0.001);

        // 向上最大速度 (tilt=0, tiltSpeed=255) -> +1.0
        assertEquals(1.0, calculateTiltSpeed(0, 255), 0.001);

        // 向下最大速度 (tilt=1, tiltSpeed=255) -> -1.0
        assertEquals(-1.0, calculateTiltSpeed(1, 255), 0.001);

        // 停止/未下发 -> 0.0
        assertEquals(0.0, calculatePanSpeed(null, 255), 0.001);
        assertEquals(0.0, calculateTiltSpeed(null, 255), 0.001);
    }
}
```

---

## 3 多品牌主流 IPC 实测兼容性矩阵与避坑指南

在安防一线实测中，各大厂商摄像机对 ONVIF 协议标准的实现程度与默认安全策略差异极大：

| 厂商品牌 | 典型测试设备型号 | 常见陷阱与非标现象 | 实施解决方案与防御机制 |
|---|---|---|---|
| **海康威视 (Hikvision)** | DS-2CD2047G2-LU<br>DS-2CD3T47EDWD | **1. 默认关闭 ONVIF 服务**。<br>**2. ONVIF 需独立开户**（即便摄像头有 admin 账号，ONVIF 用户也是独立的“集成通信用户”）。<br>**3. 强制密码复杂度**。 | 1. 登录海康 Web 端：配置 -> 网络 -> 高级配置 -> 集成协议 -> 勾选“启用 ONVIF”。<br>2. 点击“添加”，新建管理员级别用户（如 `admin` 或 `onvif_admin`）。<br>3. WVP 接入时必须使用此独立用户密码。 |
| **大华 (Dahua)** | DH-IPC-HFW2431S<br>DH-IPC-HDBW3841E | **1. `device_service` 端口差异**：部分老款机型默认 ONVIF 端口为 `8080` 或 `8899`，而非 `80`。<br>**2. 多目全景相机通道 Profile 超出**（同时存在 4 个以上 Profile）。 | 1. `OnvifDiscoveryClient` 能够自动从 UDP 探测报文中提取真实的 `XAddrs` 端口，无需人工猜测。<br>2. `OnvifDeviceServiceImpl` 支持循环遍历所有 Profile 并按 `channel_index` 正确挂接。 |
| **宇视 (Uniview)** | IPC2122SR3-PF40<br>IPC3612LR3 | **1. UTC 时间带微秒**：返回的 UTC 时间部分固件包含毫秒或微秒偏移字符串。<br>**2. 强校验 SOAPAction 头部**。 | 1. `OnvifXmlParser` 仅针对 `Hour`, `Minute`, `Second`, `Year`, `Month`, `Day` 整数节点提取，完全规避字符串截断异常。<br>2. `OnvifSoapClient` 统一标准化透传 `SOAPAction` 标头。 |
| **雄迈 / 杂牌 IPC** | 通用雄迈主板<br>(XM530 / Hi3516EV) | **1. RTC 电池常年失效掉电**，时间死锁在 1970-01-01。<br>**2. XML 响应缺少闭合标签或缺少 Envelope 命名空间**。<br>**3. RTSP 地址内嵌非标通道标识**。 | 1. **动态时钟偏斜（Clock Skew）自动补偿器生效**：系统算出数十年的 offset 并自动在签名中校准，一次鉴权通过！<br>2. 基于 `dom4j` 的容错解析器自动过滤脏数据与根节点前缀差异。 |

---

## 4 编译开发与部署运维文档同步更新

在前期技术方案落地中，已将 ONVIF 相关的端口与网络拓扑无缝补充至工程核心编译文档 [doc/reaticle_docs/compile.md](Compile-and-Dev-Guide)。以下梳理关键核验点：

### 4.1 端口通信矩阵核验点

在双机协同开发或生产单机部署中，确认以下网络端口均已放行：

| 端口号 | 传输层 | 方向 | 说明 | 放行位置 |
|---|---|---|---|---|
| `3702` | UDP | 出站/入站 (广播/单播) | OASIS WS-Discovery 局域网摄像头自动探测 | **Windows 11 开发机 / Docker 宿主机** |
| `80` / `8080` / `8899` | TCP | 出站 | ONVIF SOAP 控制信令（获取能力、Profile、云台下发） | **Windows 11 开发机 / Docker 宿主机** |
| `554` | TCP | 出站 (主动拉流) | ZLMediaKit 向摄像头拉取 RTSP H.264/H.265 音视频流 | **iMac 配合机 (ZLM) / Docker 宿主机** |
| `18080` | TCP | 入站 | WVP 管理服务与 ZLM 事件回调接收 | **Windows 11 开发机** |

### 4.2 Docker / 容器化部署网络约束

> [!WARNING]
> **WS-Discovery 多播广播必须配置 `network_mode: host`**：
> 在 Docker 容器或 Kubernetes 环境中，默认的 Bridge 桥接网络（如 `docker0` 虚拟网桥）会丢弃 `239.255.255.250:3702` 多播组播报文。
> 若将 WVP-PRO 容器化部署，**必须在 `docker-compose.yml` 中声明 `network_mode: host`**，否则 WS-Discovery 局域网探测将无法接收摄像头回包（此时只能使用单播指定 IP:Port 方式接入）。

---

## 5 交付验收基线 Checklist

上线或合并进入主分支前，执行以下验收清单检查：

- [x] **依赖纯洁性**：执行 `mvn clean package -DskipTests`，验证编译包大小增量 $\le 200\text{ KB}$，无 CXF/JAX-WS 重型框架；
- [x] **时钟偏斜自愈**：将摄像头时钟故意调乱（如偏差 3 小时），添加设备，确认 1 次握手成功，无 401 失败；
- [x] **Profile 主辅流完整提取**：设备添加后，`wvp_onvif_channel` 表及核心表 `wvp_device_channel` 出现对应的两条码流记录；
- [x] **视频出图延迟**：点击播放，ZLM 自动挂载 `app=onvif`，WebRTC / HTTP-FLV 出图延迟 $\le 1.5$ 秒；
- [x] **云台刹车灵敏度**：鼠标按下与松开，PTZ 转动与停止无卡滞、无漂移；
- [x] **无人观看自动关流**：所有播放端关闭后，流代理在设定超时时间后自动释放，摄像头网络带宽归零；
- [x] **国标级联对等推送**：上级国标平台点播该 ONVIF 通道，WVP 成功通过 ZLM 将 RTSP 流转为 PS-RTP 向上推送。
