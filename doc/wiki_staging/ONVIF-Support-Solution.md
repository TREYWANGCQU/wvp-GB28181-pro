# WVP-PRO 开源分支 ONVIF 协议支持技术实施方案

## 1 背景与需求定位

### 1.1 现状与业务痛点
WVP-PRO 原生基于 GB28181（SIP + PS-RTP）信令体系构建。在项目官方根目录 [README.md](https://github.com/TREYWANGCQU/wvp-GB28181-pro/blob/master/README.md) 中，ONVIF 协议被归属于 **“闭源内容”**，开源版本仅提供了通用的“拉流代理（Stream Proxy）”，无法实现局域网设备自发现（WS-Discovery）、SOAP 信令握手、PTZ 八向平滑控制、变倍调焦、预置位管理以及摄像头硬件参数自动获取。

在私有化安防监控与中小型边缘部署场景中，大量存量监控摄像头（海康威视、大华、宇视、雄迈及通用 IPC）原生支持 **ONVIF Profile S（基础视频与 PTZ）与 Profile T（高级视频流）**，但并未开启或无法接入 GB28181 SIP 服务。在本开源分支中，采用**轻量原生自研 SOAP 引擎**直接落地 ONVIF 支持，能够以极低侵入性打通非国标设备的标准化纳管。

### 1.2 建设目标
1. **多协议统一抽象**：基于 WVP-PRO 既有的 `ChannelDataType` 策略抽象工厂，将 ONVIF 设备作为与国标通道同等级的统一业务源纳管。
2. **轻量健壮的通信引擎**：自主实现轻量级 SOAP 通信底座，避免引入重型 JAX-WS / CXF 依赖，零新增第三方重型 Jar 包，原生适配 Java 21 虚拟线程。
3. **功能闭环**：
   - **WS-Discovery**：局域网 UDP 多播设备自动探测与单播主动扫描；
   - **核心 Device & Media 信令**：设备硬件信息查询、时钟自校准、Profile 解析、主辅码流 RTSP 地址提取、抓拍快照；
   - **PTZ 云台与预置位**：八向平滑转动（ContinuousMove）、变倍缩放（Zoom）、即时停止（Stop）以及预置位增删调；
   - **流媒体无缝调度**：获取 RTSP 地址后自动托管至 ZLMediaKit（ZLM）生成 WebRTC / HTTP-FLV / HLS 播放流，支持按需拉流与无人观看自动停流；
   - **Web UI 完整集成**：在 Vue 前端新增 ONVIF 设备管理、一键局域网发现、通道列表与云台控制面板。

---

## 2 核心技术路线：轻量自研 SOAP 通信引擎

本工程**唯一选定并深度实施**“轻量原生自研 SOAP 引擎 + 宽容解析”技术路线，不再采用重量级 CXF 生成代码或陈旧第三方库。

### 2.1 技术设计原理
1. **传输管道**：基于 JDK 21 原生 `java.net.http.HttpClient`，天然支持 HTTP/1.1 与 HTTP/2，完美适配 Spring Boot 3.4+ 的虚拟线程机制（Virtual Threads），在高并发扫描与多设备心跳交互下内存与线程开销极低。
2. **报文装配（Template Engine）**：针对 ONVIF Device、Media、PTZ 服务的标准信令（如 `GetDeviceInformation`、`GetCapabilities`、`GetProfiles`、`GetStreamUri`、`ContinuousMove`、`Stop`），采用轻量化 XML 模板引擎精准填充变量并计算 SOAP Action 与命名空间。
3. **宽容型 XML 解析**：利用工程已有依赖 `dom4j` 与 JDK 原生 XML 解析工具，采用宽容模式提取目标节点文本与属性，自动忽略不同安防厂商（如老旧固件或山寨 IPC）在 SOAP 响应中常见的命名空间缺失、大小写混乱及非标包裹层。
4. **动态时钟偏斜（Clock Skew）自动补偿**：实现原生 WS-Security UsernameToken 生成器，先通过无鉴权 `GetSystemDateAndTime` 获取设备硬件 UTC 时间，在内存中动态维护 `clock_offset`，杜绝因摄像头 RTC 掉电造成的 401 Unauthorized 鉴权失败。

---

## 3 协议硬约束与边界契约矩阵

| 交互环节 | 依赖规范与 RFC | 物理与网络硬约束 | 容错与自愈策略 |
|---|---|---|---|
| **设备自发现** | OASIS WS-Discovery v1.1 | 监听/发送 UDP 多播地址 `239.255.255.250:3702`。<br>**宿主网络约束**：在 Docker/Colima 容器化部署中，默认 Bridge 桥接网络阻断多播，**容器必须配置 `network_mode: host`**。 | 若多播无响应或处于跨三层网段环境，支持通过单播指定“IP:Port”直接发起探测，或直接请求 `device_service` 握手。 |
| **信令安全鉴权** | OASIS WS-Security 1.1 (UsernameToken) | `PasswordDigest = Base64(SHA-1(Nonce + Created + Password))`。<br>**时钟硬约束**：摄像头硬件时钟未配置 NTP 或掉电后极易偏移，偏差超 5 分钟直接报鉴权失败。 | 每次设备初始化或心跳时，首先调用 `GetSystemDateAndTime` 计算偏移：`clock_offset = cameraUtcTime - hostUtcTime`，生成 `Created` 时间戳时强制增加该偏移量。 |
| **媒体流调度** | RFC 2326 (RTSP 1.0) / ONVIF Profile S | ONVIF 仅负责信令协商与 RTSP URL 下发，不直接承载媒体流传输。 | WVP 调用 ONVIF `GetStreamUri` 获取标准 RTSP 地址，调用 ZLMediaKit `/index/api/addStreamProxy` 接管推拉流，由 ZLM 转为 WebRTC/HTTP-FLV。 |
| **云台速度模型** | ONVIF PTZ Specification | ONVIF 采用空间归一化浮点速度：$x, y, z \in [-1.0, 1.0]$。<br>WVP-PRO 原生信令采用国标整数范围 $[0, 255]$。 | 建立线性映射模型：$V_{\text{onvif}} = \frac{V_{\text{wvp}}}{255.0} \times \text{DirectionSign}$；松开操控按键时强制下发带有明确 `PTZ:Stop` 语义的 SOAP 请求。 |

---

## 4 仓库目录规划与代码组织

在当前项目结构下，严格遵循原有模块划分风格，在 `com.genersoft.iot.vmp` 下新增独立的 `onvif` 业务包，前后端与数据库目录规划如下：

```text
wvp-GB28181-pro/
├── src/main/java/com/genersoft/iot/vmp/
│   ├── common/enums/
│   │   └── ChannelDataType.java                  # [MODIFY] 注册 ONVIF = 4 通道类型常量与服务名前缀
│   └── onvif/                                    # [NEW] ONVIF 完整独立协议模块
│       ├── bean/                                 # 领域与传输实体
│       │   ├── OnvifDevice.java                  # ONVIF 物理设备实体
│       │   ├── OnvifChannel.java                 # ONVIF 逻辑通道实体 (Profile 对应)
│       │   ├── OnvifProbeResult.java             # WS-Discovery 搜寻发现结果对象
│       │   ├── OnvifProfile.java                 # 码流 Profile 描述实体
│       │   └── OnvifPTZControl.java              # 云台控制参数载荷
│       ├── client/                               # 轻量原生 SOAP 协议栈核心通信引擎
│       │   ├── OnvifDiscoveryClient.java         # WS-Discovery UDP 多播与单播搜寻器
│       │   ├── OnvifSoapClient.java              # 基于 JDK 21 HttpClient 的 SOAP 传输核心
│       │   ├── OnvifSecurityHeader.java          # WS-Security UsernameToken 签名与时钟补偿器
│       │   ├── OnvifXmlBuilder.java              # 预编译 SOAP XML 报文组装工厂
│       │   └── OnvifXmlParser.java               # 基于 dom4j 的宽容型 XML 响应抽取器
│       ├── dao/                                  # 数据持久化
│       │   ├── OnvifDeviceMapper.java            # 设备数据访问 Mapper
│       │   ├── OnvifChannelMapper.java           # 通道数据访问 Mapper
│       │   └── provider/                         # 动态 SQL Provider
│       ├── service/                              # 业务服务层
│       │   ├── IOnvifDeviceService.java          # 设备管理与 Profile 同步服务
│       │   ├── IOnvifDiscoveryService.java       # 局域网搜寻与探针管理服务
│       │   ├── IOnvifPTZService.java             # PTZ 转动、变倍与预置位控制服务
│       │   └── impl/
│       │       ├── OnvifDeviceServiceImpl.java
│       │       ├── OnvifDiscoveryServiceImpl.java
│       │       ├── OnvifPTZServiceImpl.java
│       │       ├── SourcePlayServiceForOnvifImpl.java    # 实现 ISourcePlayService (@Service("sourceChannelPlayService4"))
│       │       ├── SourcePTZServiceForOnvifImpl.java     # 实现 ISourcePTZService (@Service("sourceChannelPTZService4"))
│       │       └── SourceOtherServiceForOnvifImpl.java   # 实现 ISourceOtherService (@Service("sourceChannelOtherService4"))
│       └── controller/                           # REST API 表现层
│           ├── OnvifDeviceController.java        # 设备增删改查、同步与通道列表 (/api/onvif/device)
│           └── OnvifDiscoveryController.java     # 触发局域网搜寻与结果回传 (/api/onvif/discovery)
│
├── 数据库/
│   └── 2.7.4/
│       └── 增量-onvif.sql                         # [NEW] MySQL/PG/H2 增量建表脚本
│
└── web/src/
    ├── api/
    │   └── onvif.js                              # [NEW] 前端 ONVIF RESTful API 请求封装
    ├── views/
    │   └── onvif/                                # [NEW] 前端管理视图
    │       ├── index.vue                         # 设备与通道主台账页面
    │       ├── deviceDiscovery.vue               # 局域网一键搜寻向导对话框
    │       └── ptzController.vue                 # 八向云台操控盘与预置位浮窗
    └── router/
        └── index.js                              # [MODIFY] 注册 /onvif 导航路由
```

---

## 5 实施细节深度展开

### 5.1 依赖库审计（Zero-New-Dependency）
经过全面代码与依赖扫描，本方案**完全不需要在 `pom.xml` 中引入任何新的 Maven 依赖**：
- **HTTP / 网络传输**：直接使用 JDK 21 标准库自带的 `java.net.http.HttpClient` 与 `java.net.DatagramSocket`，零外部依赖，天然适配虚拟线程；
- **XML 模板与解析**：直接复用项目中现有的 `org.dom4j`（已存在于运行时类路径，参见 [`XmlUtil.java`](https://github.com/TREYWANGCQU/wvp-GB28181-pro/blob/master/src/main/java/com/genersoft/iot/vmp/gb28181/utils/XmlUtil.java)）以及 JDK 自带的 `javax.xml.parsers`；
- **安全哈希计算**：直接使用 JDK 标准库 `java.security.MessageDigest`（SHA-1 计算）和 `java.util.Base64`。

**依赖审计结论**：`pom.xml` 依赖项保持 100% 纯净，编译后 Jar 包增量仅为自研 Java 字节码体积（$\le 200\text{ KB}$）。

### 5.2 WS-Discovery 局域网探测器实现细节
1. **多播配置**：
   - 目标多播地址：`239.255.255.250:3702` (UDP)；
   - 本地绑定端口：随机空闲 UDP 端口；
   - 超时策略：发送 Probe 报文后，非阻塞循环接收回包，窗口期设定为 2.5 ~ 3 秒。
2. **Probe 报文规范**：
   ```xml
   <?xml version="1.0" encoding="utf-8"?>
   <Envelope xmlns:dn="http://www.onvif.org/ver10/network/wsdl"
             xmlns="http://www.w3.org/2003/05/soap-envelope">
     <Header>
       <wsa:MessageID xmlns:wsa="http://schemas.xmlsoap.org/ws/2004/08/addressing">uuid:UUID_STRING</wsa:MessageID>
       <wsa:To xmlns:wsa="http://schemas.xmlsoap.org/ws/2004/08/addressing">urn:schemas-xmlsoap-org:ws:2005:04:discovery</wsa:To>
       <wsa:Action xmlns:wsa="http://schemas.xmlsoap.org/ws/2004/08/addressing">http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</wsa:Action>
     </Header>
     <Body>
       <Probe xmlns="http://schemas.xmlsoap.org/ws/2005/04/discovery">
         <Types>dn:NetworkVideoTransmitter</Types>
       </Probe>
     </Body>
   </Envelope>
   ```
3. **回包解析机制**：
   - 从接收到的 `ProbeMatches` 报文提取 `XAddrs`（如 `http://192.168.1.108/onvif/device_service`）与 `Scopes`（提取厂商、硬件型号及位置信息）。

### 5.3 动态时钟补偿与 WS-Security 签名生成算法
1. **时钟偏斜测算**：
   - 首次连接时，通过免认证向 `device_service` 发送 `GetSystemDateAndTime` 请求；
   - 解析摄像机返回的 `UTCDateTime`（年、月、日、时、分、秒）；
   - 计算偏移量：
     $$\Delta t_{\text{offset}} = T_{\text{camera\_utc}} - T_{\text{host\_utc}}$$
   - 将 $\Delta t_{\text{offset}}$ 缓存至 `OnvifDevice` 内存模型中（毫秒级）。
2. **UsernameToken 生成算法**：
   - 生成 16 字节真随机数作为 `Nonce`；
   - 计算校准后的时间戳：
     $$T_{\text{created}} = \text{Instant.now().plusMillis}(\Delta t_{\text{offset}})\text{.truncatedTo(SECONDS).toString()}$$
   - 生成 `PasswordDigest`：
     $$\text{PasswordDigest} = \text{Base64}\Big(\text{SHA-1}\big(\text{Nonce}_{\text{raw}} + T_{\text{created\_bytes}} + \text{Password}_{\text{bytes}}\big)\Big)$$
3. **SOAP Security Header 结构**：
   ```xml
   <wsse:Security xmlns:wsse="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd"
                  xmlns:wsu="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd">
     <wsse:UsernameToken>
       <wsse:Username>USERNAME</wsse:Username>
       <wsse:Password Type="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest">PASSWORD_DIGEST</wsse:Password>
       <wsse:Nonce EncodingType="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary">NONCE_BASE64</wsse:Nonce>
       <wsu:Created>2026-09-08T09:45:00Z</wsu:Created>
     </wsse:UsernameToken>
   </wsse:Security>
   ```

### 5.4 PTZ 坐标归一化与即时转动映射
WVP-PRO 前端下发的 PTZ 速度参数为国标标准整数：水平速度 $V_{\text{pan}} \in [0, 255]$、垂直速度 $V_{\text{tilt}} \in [0, 255]$。
1. **速度线性映射公式**：
   $$v_x = \frac{V_{\text{pan}}}{255.0} \times \text{Sign}(x), \quad v_y = \frac{V_{\text{tilt}}}{255.0} \times \text{Sign}(y), \quad v_z = \frac{V_{\text{zoom}}}{255.0} \times \text{Sign}(z)$$
   其中 $\text{Sign} \in \{-1, 0, 1\}$ 表示运动方向。
2. **ContinuousMove 报文装配**：
   ```xml
   <Envelope xmlns="http://www.w3.org/2003/05/soap-envelope"
             xmlns:ptz="http://www.onvif.org/ver20/ptz/wsdl"
             xmlns:tt="http://www.onvif.org/ver10/schema">
     <!-- Header 嵌入 WS-Security Token -->
     <Body>
       <ptz:ContinuousMove>
         <ptz:ProfileToken>PROFILE_TOKEN</ptz:ProfileToken>
         <ptz:Velocity>
           <tt:PanTilt x="0.5" y="0.0" space="http://www.onvif.org/ver10/tptz/PanTiltSpaces/VelocityGenericSpace"/>
           <tt:Zoom x="0.0" space="http://www.onvif.org/ver10/tptz/ZoomSpaces/VelocityGenericSpace"/>
         </ptz:Velocity>
       </ptz:ContinuousMove>
     </Body>
   </Envelope>
   ```
3. **Stop 停止报文装配**：
   当用户在前端松开按键时，下发带 `PanTilt=true` 与 `Zoom=true` 的 `ptz:Stop` 报文，确保摄像机立即制动。

### 5.5 ZLMediaKit StreamProxy 联动与流生命周期
1. **点播触发（Play）**：
   - 客户端请求播放时，`SourcePlayServiceForOnvifImpl` 提取该通道的 RTSP URL；
   - 后端调用 ZLM REST API `/index/api/addStreamProxy`：
     - `app`: `onvif`；
     - `stream`: 规则命名（如 `onvif_{deviceId}_{channelId}`）；
     - `url`: 摄像机 RTSP 流完整地址（内置脱敏凭证）；
     - `enable_hls`: 1, `enable_mp4`: 0, `rtp_type`: 0 (TCP/UDP 自适应)；
   - 监听 ZLM `on_stream_changed` Hook 回调，确认收到媒体流后向前端返回播放地址（WebRTC / HTTP-FLV）。
2. **无人观看自动停流（Auto-Close）**：
   - 监听 ZLM 的 `on_stream_none_reader` Hook 回调；
   - 当该流所有观看端断开且超时（如 30 秒无读者），自动调用 `/index/api/delStreamProxy` 销毁拉流代理，停止从摄像机拉流，保护局域网带宽。

---

## 6 数据库持久化结构设计

在现有数据库脚本基础上，新增专用台账表与通道关联映射：

```sql
-- -------------------------------------------------------------
-- 1. ONVIF 物理根设备表
-- -------------------------------------------------------------
DROP TABLE IF EXISTS wvp_onvif_device;
CREATE TABLE IF NOT EXISTS wvp_onvif_device (
    id                     INT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    name                   VARCHAR(255) NOT NULL COMMENT '设备自定义名称',
    ip                     VARCHAR(50) NOT NULL COMMENT '设备 IPv4 地址',
    port                   INT NOT NULL DEFAULT 80 COMMENT 'ONVIF 服务端口 (通常为 80/8080/8899)',
    username               VARCHAR(100) NOT NULL COMMENT 'ONVIF 认证用户名',
    password               VARCHAR(100) NOT NULL COMMENT 'ONVIF 认证密码',
    device_service_url     VARCHAR(255) NOT NULL COMMENT '设备服务完整 XAddr',
    media_service_url      VARCHAR(255) COMMENT 'Media 服务完整 XAddr',
    ptz_service_url        VARCHAR(255) COMMENT 'PTZ 服务完整 XAddr',
    imaging_service_url    VARCHAR(255) COMMENT 'Imaging 服务完整 XAddr',
    manufacturer           VARCHAR(100) COMMENT '厂商信息 (Hikvision, Dahua 等)',
    model                  VARCHAR(100) COMMENT '设备型号',
    firmware_version       VARCHAR(100) COMMENT '固件版本',
    serial_number          VARCHAR(100) COMMENT '硬件序列号',
    mac                    VARCHAR(50) COMMENT 'MAC 地址',
    clock_offset           BIGINT DEFAULT 0 COMMENT '服务器与摄像头的时钟差 (毫秒，用于 WS-Security)',
    status                 TINYINT(1) DEFAULT 1 COMMENT '在线状态 (1:在线, 0:离线)',
    media_server_id        VARCHAR(50) DEFAULT 'auto' COMMENT '绑定的流媒体服务ID',
    create_time            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_onvif_ip_port (ip, port)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='ONVIF 物理设备表';

-- -------------------------------------------------------------
-- 2. ONVIF 逻辑码流通道表 (与 Profile 一对一映射)
-- -------------------------------------------------------------
DROP TABLE IF EXISTS wvp_onvif_channel;
CREATE TABLE IF NOT EXISTS wvp_onvif_channel (
    id                     INT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    device_id              INT NOT NULL COMMENT '关联的 wvp_onvif_device 主键ID',
    channel_index          INT NOT NULL DEFAULT 1 COMMENT '物理镜头序号 (多目相机使用)',
    profile_token          VARCHAR(100) NOT NULL COMMENT 'ONVIF Profile Token (如 Profile_1)',
    name                   VARCHAR(255) COMMENT 'Profile 名称 (如 MainStream / SubStream)',
    video_encoding         VARCHAR(50) COMMENT '视频编码 (H264 / H265)',
    resolution             VARCHAR(50) COMMENT '分辨率 (如 1920x1080)',
    frame_rate             INT COMMENT '帧率',
    bitrate                INT COMMENT '码率 (kbps)',
    rtsp_url               VARCHAR(512) COMMENT '解析出的标准 RTSP 流拉取地址',
    snapshot_url           VARCHAR(512) COMMENT '抓拍快照 URL',
    has_ptz                TINYINT(1) DEFAULT 0 COMMENT '该 Profile 是否具备 PTZ 控制能力 (1:是, 0:否)',
    create_time            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    CONSTRAINT fk_onvif_channel_dev FOREIGN KEY (device_id) REFERENCES wvp_onvif_device(id) ON DELETE CASCADE,
    UNIQUE KEY uk_onvif_dev_profile (device_id, profile_token)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='ONVIF 码流通道表';
```

**与平台核心表的挂接方式**：
每个 `wvp_onvif_channel` 在完成设备探测同步后，自动向核心通道表 `wvp_device_channel` 插入对等记录：
- `data_type = 4` (`ChannelDataType.ONVIF`)；
- `data_device_id = wvp_onvif_channel.id`；
- `device_id` 填充虚拟国标编码；
- `ptz_type = has_ptz ? 1 : 0`；
- `status = wvp_onvif_device.status`。

---

## 7 影响范围评估与各层解耦边界

| 系统层级 | 影响范围分析 | 风险与防御机制 |
|---|---|---|
| **核心数据库** | **零结构性破坏**。不修改任何现有表结构，仅新增 `wvp_onvif_device` 与 `wvp_onvif_channel`，并在 `wvp_device_channel` 中以 `data_type=4` 增加行级记录。 | 物理隔离。删除 ONVIF 设备级联清除对应通道，对 GB28181 通道零影响。 |
| **Java 后端核心** | **完全解耦**。在 [`ChannelDataType.java`](https://github.com/TREYWANGCQU/wvp-GB28181-pro/blob/master/src/main/java/com/genersoft/iot/vmp/common/enums/ChannelDataType.java) 注册常量后，核心调度逻辑（播放、云台、级联）自动通过 Spring 策略 Map 分流。 | 采用独立包 `com.genersoft.iot.vmp.onvif`，未触碰 SIP 信令栈任何代码。 |
| **ZLMediaKit 交互** | 仅通过现有 RESTful 接口 `/index/api/addStreamProxy` 挂载流。 | 媒体流由 ZLM 原生转码分发，对 WVP 后端 Java 进程零内存与 CPU 编解码负担。 |
| **国标级联推送** | **无缝支持**。由于映射到了 `wvp_device_channel`，上级国标平台在执行目录查询与点播时，WVP 会自动将该 ONVIF 通道的 RTSP 流通过 ZLM 转为 PS-RTP 向上推送。 | 实现了“接入 ONVIF -> 向上级推送 GB28181”的高价值国标网关转换能力。 |
| **编译与打包** | 构建脚本与运行环境保持完全兼容，不产生额外环境依赖。 | 打包产物依然为单一标准的 `wvp-pro-2.7.4.jar`。 |

---

## 8 对编译指南 `doc/reaticle_docs/compile.md` 的影响评估

经全面比对，本方案**对 [doc/reaticle_docs/compile.md](Compile-and-Dev-Guide) 具有极佳的兼容性，无任何阻断性或破坏性影响**，具体评估如下：

### 8.1 编译工具链与环境基线（零影响）
- **JDK 要求**：继续保持 Java 21（LTS），充分利用已规划的虚拟线程特性，无需安装额外 JDK 扩展；
- **Maven 构建**：构建命令保持 `mvn clean package -DskipTests` 不变，无新增插件或私有仓库配置；
- **Node.js 前端构建**：保持 Node 18~24 兼容参数 `$env:NODE_OPTIONS="--openssl-legacy-provider"`，构建产物自动输出到 `src/main/resources/static`。

### 8.2 配合机 Docker 中间件（零影响）
- iMac 配合机运行的 MySQL 8.0、Redis 7.0 与 ZLMediaKit（含 WebRTC 模块）保持原有编排配置不变，无需挂载新插件或修改 `config.ini`。

### 8.3 需微调补充的文档细节（增量优化项）
在 `compile.md` 中仅需进行两处小范围增量说明更新：
1. **第 1 章（服务架构概述）**：
   - 声明本分支已原生支持 **GB28181-2016/2022** 与 **ONVIF Profile S/T** 双协议接入。
2. **第 8 章（双机协同网络与防火墙通信矩阵）**：
   - 在表格与放行规则中，追加关于 ONVIF 设备发现与拉流的端口说明：
     - **3702/UDP**：局域网 WS-Discovery 多播设备发现（Windows 11 主机出入站）；
     - **554/TCP**：ZLMediaKit 主动向摄像头拉取 RTSP 流（配合机出站）。

---

## 9 实施进度与验收标准

### 9.1 阶段计划 (WBS) 与实施细节指引
1. **第一阶段：通信引擎底座**：完成 `OnvifSoapClient`、`OnvifSecurityHeader`（时钟补偿）、`OnvifDiscoveryClient`（UDP 探测）与 XML 模板工厂。详见实施文档：[onvif-implementation/phase-1-soap-engine-and-discovery.md](ONVIF-Phase-1-SOAP-Engine-and-Discovery)。
2. **第二阶段：业务与通道同步**：完成数据库 Mapper、设备连接验证、Profile 主辅码流解析与 `wvp_device_channel` 级联同步。详见实施文档：[onvif-implementation/phase-2-persistence-and-channel-sync.md](ONVIF-Phase-2-Persistence-and-Channel-Sync)。
3. **第三阶段：流调度与云台对接**：实现 `SourcePlayServiceForOnvifImpl`（ZLM 代理接入）与 `SourcePTZServiceForOnvifImpl`（云台八向与变倍制动）。详见实施文档：[onvif-implementation/phase-3-media-proxy-and-ptz-control.md](ONVIF-Phase-3-Media-Proxy-and-PTZ-Control)。
4. **第四阶段：前端 UI 与联调**：实现 `/onvif` 路由、局域网一键搜寻弹窗与监控分屏 PTZ 控件联动。详见实施文档：[onvif-implementation/phase-4-restful-api-and-web-ui.md](ONVIF-Phase-4-RESTful-API-and-Web-UI)。
5. **第五阶段：文档同步与测试固化**：同步更新 `compile.md` 端口矩阵，完成多品牌 IPC 实测。详见实施文档：[onvif-implementation/phase-5-testing-verification-and-doc-sync.md](ONVIF-Phase-5-Testing-Verification-and-Doc-Sync)。

> 完整实施目录索引可参阅：[onvif-implementation/README.md](ONVIF-Implementation-Guide)

### 9.2 最终验收指标
- **发现效率**：同局域网内探测 3 秒内接收所有在线摄像机响应；
- **鉴权容错**：摄像头硬件时钟偏差 1 小时以上，系统自动补偿，签名认证一次通过（零 401 失败）；
- **点播延迟**：点击播放后，后端驱动 ZLM 建立代理并在 1.5 秒内出图（支持 WebRTC / HTTP-FLV）；
- **云台手感**：云台转动与变倍按键响应延迟 $\le 200\text{ ms}$，松开按键后即刻精准停止，无超调失控；
- **纯洁度验证**：`mvn dependency:tree` 确认无任何 CXF/JAX-WS 重型依赖，编译增量 $\le 200\text{ KB}$。
