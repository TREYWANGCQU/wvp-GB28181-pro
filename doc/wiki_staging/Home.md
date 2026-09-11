# WVP-PRO (GB28181 & ONVIF 双协议分支) 技术全景文档

欢迎查阅 **WVP-PRO** 深度扩展分支的技术全景 Wiki。本项目在原生支持 **GB28181-2016 / 2022** 国标视频协议的基础上，**自研了高性能原生 ONVIF 协议支持**（WS-Discovery 局域网探测、时钟偏斜动态补偿 WS-Security、Profile 码流解析、ZLM 自动拉流接管与 PTZ 云台坐标归一化控制），并实现了 **Docker All-in-One 多架构一体化镜像极速交付（约300MB）**。

---

## 🌟 核心特性与架构升级

`mermaid
flowchart TD
    subgraph IPC["安防摄像机与监控设备群"]
        GB_CAM["GB28181 国标摄像头<br/>(海康 / 大华 / 宇视等)"]
        ONVIF_CAM["ONVIF 摄像头<br/>(Profile S / T / 全网探测)"]
    end

    subgraph WVP_STACK["WVP-PRO 统一信令与管理调度底座"]
        direction TB
        SIP_ENGINE["GB28181 SIP 信令栈<br/>(JAIN-SIP / 状态维护 / 目录同步)"]
        ONVIF_ENGINE["自研轻量 SOAP 引擎<br/>(WS-Discovery / WS-Security / PTZ 归一化)"]
        BIZ_CORE["核心调度与数据中心<br/>(Spring Boot 3.4 / JDK 21 虚拟线程)"]
        WEB_UI["现代响应式 Web UI 控制台<br/>(Vue 2 / 分屏预览 / 操控盘)"]
    end

    subgraph MEDIA["高性能流媒体分发中枢"]
        ZLM["ZLMediaKit (C++20 高性能引擎)<br/>- RTP 国标收流池<br/>- ONVIF RTSP 代理接管<br/>- WebRTC / FLV / HLS / 对讲分发"]
    end

    subgraph STORAGE["数据与缓存基础设施"]
        DB[(MariaDB / MySQL 8.0)]
        CACHE[(Redis 7.0 缓存)]
    end

    GB_CAM <-->|SIP 信令 :8116| SIP_ENGINE
    GB_CAM -->|RTP 媒体流 :30000-30050| ZLM

    ONVIF_CAM <-->|UDP 探测 :3702 / HTTP SOAP :80| ONVIF_ENGINE
    ONVIF_CAM -->|RTSP 媒体流 :554| ZLM

    SIP_ENGINE <--> BIZ_CORE
    ONVIF_ENGINE <--> BIZ_CORE
    BIZ_CORE <-->|RESTful & Webhook| ZLM
    BIZ_CORE <--> DB
    BIZ_CORE <--> CACHE
    WEB_UI <-->|HTTP :18080| BIZ_CORE
`

1. **双协议全功能融合**：打破传统国标平台的接入限制，局域网内的海康、大华、宇视、雄迈等 ONVIF 摄像头通过一键探测即可秒级入网，统一汇入设备通道树并赋予同等的点播、录像与 PTZ 控制能力；
2. **Zero-New-Dependency 依赖纯洁性**：坚持采用 JDK 21 原生 HttpClient 与现有 dom4j 宽容解析实现 SOAP 引擎，零引入 CXF、Axis 等笨重第三方依赖，完美契合 Java 21 虚拟线程高并发特性；
3. **Docker All-in-One 极速全栈交付**：首创「WVP-PRO + ZLMediaKit + MariaDB + Redis」四合一生产级单容器运行体系，支持 linux/amd64 与 linux/arm64 双架构，原生集成自愈 Entrypoint 防御，开箱即用；
4. **自动化工程生态闭环**：通过本地 AI Skill 智能编目与 GitHub Actions 云端 CI，实现技术文档与工程代码变更的秒级持续集成与 Wiki 自动发布。

---

## 📚 知识库全景导航

### 🛠️ 1. 编译与本地开发指南
- **[编译与开发全指南](Compile-and-Dev-Guide)**：三机分工拓扑、Windows 11 开发环境、前端/后端编译及测试机离线部署全指南。

---

### 🐳 2. Docker All-in-One 全栈交付
- **[All-in-One 镜像合并打包架构方案](Docker-All-in-One-Solution)**：四合一生产级极简运行底座与多阶段构建瘦身设计。
- **[All-in-One 双机协同编译与 Docker Hub 发布实施细则](Docker-All-in-One-Implementation)**：多架构编译、自愈 Entrypoint 守护与 Docker Hub 镜像中心拉取手册。

---

### 📹 3. ONVIF 原生协议扩展全体系
- **[ONVIF 协议支持技术实施方案](ONVIF-Support-Solution)**：原生轻量自研 SOAP 引擎与架构契约矩阵。
- **[五阶段实施工程指南总览](ONVIF-Implementation-Guide)**：五阶段实施架构全景、类图结构与交付验收门禁。
- **[阶段一：通信底座与网络探测](ONVIF-Phase-1-SOAP-Engine-and-Discovery)**：原生 HttpClient、时钟偏斜动态补偿与 WS-Discovery 探测器。
- **[阶段二：持久化与通道同步](ONVIF-Phase-2-Persistence-and-Channel-Sync)**：增量 DDL 设计、OnvifDevice 模型与通道归一化挂接。
- **[阶段三：流媒体调度与云台控制](ONVIF-Phase-3-Media-Proxy-and-PTZ-Control)**：ZLM StreamProxy 代理拉流接管与 PTZ 坐标归一化模型。
- **[阶段四：RESTful API 与 Web UI](ONVIF-Phase-4-RESTful-API-and-Web-UI)**：控制器端点设计、Axios API 封装与 Vue 2 设备台账交互。
- **[阶段五：联调验证与交付基线](ONVIF-Phase-5-Testing-Verification-and-Doc-Sync)**：JUnit 5 自动化测试与海康/大华/宇视/雄迈多品牌 IPC 实测矩阵。

---

### 🚀 4. 国标特性与业务扩展
- **[国标级联自定义通道编码批量导入方案](Feats-GB-Cascade-Custom-Channel-Batch-Excel-Solution)**：基于 EasyExcel 的多级目录与通道编码批量解析、重命名映射与下级推送机制。
- **[GB28181 语音对讲与广播升级改造方案](Feats-GB28181-Voice-Talk-and-Broadcast-Upgrade-Solution)**：广播与对讲信令解耦、ZLM 端口动态分配与双向音频流传输优化方案。

---

### 🔬 5. 前沿探索与源码剖析
- **[GB28181 语音对讲与广播故障根因剖析报告](Research-GB28181-Voice-Talk-and-Broadcast-Failure-Analysis)**：从抓包证据、SIP INVITE 流程到音频编码协商的端到端全链路故障复盘。

---

### 🩺 6. 调试与故障排查
- **[ONVIF 接入调试与优化方案 (修订版)](ONVIF-Debug-Optimization-Plan)**：时钟偏斜鉴权失败、WS-Discovery 端口占用与 Profile 空指针排查。
- **[ONVIF 批量导出覆盖与 ID 复用优化方案](Debug-2026-09-11-Onvif-Batch-Export-Overwrite-and-Id-Reuse-Plan)**：设备批量导入时主键冲突自愈与配置回填方案。

---

### ⚙️ 7. 工程架构与工具链
- **[GitHub Wiki 自动化改造与持续发布系统方案](Wiki-Automation-Solution)**：本地 AI Skill 智能编目 + 受控 Staging 暂存 + GitHub CI 自动同步架构设计。

---

## ⚡ 极速起步 (Quick Start)

### 选项 A：使用 Docker 极速体验 (推荐)
`ash
docker run -d \
  --name wvp-aio \
  --restart unless-stopped \
  --net=host \
  -v /opt/wvp-aio-data/data/mysql:/var/lib/mysql \
  -v /opt/wvp-aio-data/data/record:/opt/media/bin/www/record \
  -v /opt/wvp-aio-data/logs:/opt/wvp/logs \
  reaticle/wvp-pro-aio:2.7.4
`
访问 http://<宿主机IP>:18080，默认账号密码：dmin / dmin。

### 选项 B：源码开发启动
1. 查阅 [编译与开发指南](Compile-and-Dev-Guide) 准备基础环境；
2. 启动测试机 MySQL/Redis/ZLM 基础设施；
3. 执行前端编译并将静态资源写入后端；
4. 运行 com.genersoft.iot.vmp.VManageBootstrap 开启本地开发与调试。

---

## 🤝 参与维护与反馈

- **GitHub Issues**：[提交 Bug 报告或功能建议](https://github.com/TREYWANGCQU/wvp-GB28181-pro/issues)
- **分支维护人**：y.wang@reaticle.com
- **致谢**：感谢原版 WVP-PRO 与 ZLMediaKit 开源团队做出的卓越贡献！