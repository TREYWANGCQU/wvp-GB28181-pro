<!-- doc/wiki_staging/Release-Notes-v2.8.0.md -->
# Release Notes - v2.8.0 (2026-09-11)

## 📌 版本概述 (Overview)

WVP-PRO **v2.8.0** 是自 2.7.x 系列以来最重要的里程碑式**次大版本 (Minor Release)** 升级。
本版本正式引入了**自研原生 ONVIF 协议全生命周期支持**，打破了传统国标安防平台对非国标摄像机接入困难的壁垒；推出了极简的 **Docker All-in-One 四合一交付体系**（支持 `linux/amd64` 与 `linux/arm64` 双架构）；同时针对高频业务场景实现了**国标级联自定义通道编码批量 Excel 导入**与 **GB28181 语音对讲/广播信令解耦重构**。

凭借**「Zero-New-Dependency」**架构纯洁性原则，全量新协议与能力完全依托 JDK 21 虚拟线程与原生 HttpClient 构建，为大规模安防视频汇聚平台提供了极致的并发性能与可靠性保障。

---

## 🚀 核心特性 (Key Features)

### 1. ONVIF 原生协议栈全生命周期闭环 (Phase 1 ~ Phase 5)
- **自研轻量 SOAP 通信引擎**：遵循 Zero-New-Dependency 依赖纯洁性原则，零引入 CXF/Axis 等笨重第三方库，基于 JDK 21 原生 HttpClient 与 dom4j 宽容解析实现高性能 SOAP 1.2 客户端；
- **全网自愈型 WS-Discovery 探测器**：支持局域网 UDP 3702 多播广播与单播探测，自动嗅探在线 IPC，实现海康、大华、宇视、雄迈等跨品牌设备秒级自动发现；
- **动态时钟偏斜自愈补偿 (Clock Skew Compensation)**：针对设备端 RTC 电池失效或时钟漂移导致的 WS-Security 401/400 鉴权失败，自研往返时延测算与毫秒级时钟动态偏斜补偿机制；
- **通道模型归一化与数据持久化**：新增 `wvp_onvif_device` 核心实体，自动解析 Profile S/T 媒体令牌 (Media Token)，智能提取主/子码流 RTSP 地址，无缝映射入 WVP 统一设备通道树；
- **流媒体拉流代理接管 (StreamProxy)**：深度集成 ZLMediaKit，提供按需拉流、空闲自动断流守护与断线平滑重连机制，多路并发下保持低内存占用；
- **云台 PTZ 空间坐标归一化控制**：将各厂商非标速度转换为 $[-1.0, 1.0]$ 归一化三维空间向量，支持八向连续变倍、预置位检索、设定与调用；
- **现代响应式控制台集成**：控制台提供 ONVIF 设备管理、一键全网探测发现弹窗、虚拟云台操控盘及多码流分屏点播。

---

## 🐳 容器与交付体系 (Docker & Infrastructure)

### 1. Docker All-in-One 极速交付底座
- **四合一极简容器形态**：首创将「WVP-PRO + ZLMediaKit + MariaDB + Redis」封装为单个高可用生产级镜像（压缩后体积约 300MB），杜绝跨容器网络连通难题，实现一行命令极速起步；
- **多架构原生适配**：支持 `linux/amd64` 与 `linux/arm64` 多架构协同编译，自动生成多架构 Manifest List；
- **进程自愈与平滑退出守护**：内置基于 supervisord 的多进程守护编排，具备进程崩溃秒级拉起、健康探针检测与 SIGTERM 平滑停机清理机制；
- **单一信任源与动态版本注入**：确立以 `pom.xml` 为根信任源，配合 `scripts/bump-version.ps1` 联动更新 Docker 镜像 Tag，支持通过 `WVP_VERSION=2.8.0` 环境变量无感知热注入。

---

## ✨ 业务功能增强 (Enhancements)

### 1. 国标级联自定义通道编码批量 Excel 导入
- **批量导入导出引擎**：基于 EasyExcel 构建百万级海量通道流式读写能力，支持以 Excel 模板形式批量修改级联上级平台时的国标编码、名称、分组与目录结构；
- **校验防御与冲突自愈**：严格校验 20 位国标编码规范（类型编码、网络标识、序列号），自动检测同级重名与冲突并高亮提示。

### 2. GB28181 语音对讲与广播信令解耦重构
- **业务信令正交分离**：彻底拆解既有代码中双向语音对讲 (Voice Intercom / Talk) 与单向广播喊话 (Voice Broadcast) 的耦合混乱；
- **SDP 协商规范化**：修复音频 `sendonly` 与 `recvonly` 媒体属性反转缺陷，规范 RTP 音频端口的动态分配与媒体回环防卫，彻底解决无声、卡顿与通道泄漏问题。

---

## 🐛 缺陷修复与自愈 (Bug Fixes & Reliability)

- **ONVIF 批量导出覆盖与 ID 紧凑复用**：解决同 IP:Port 设备重复导入时的主键冲突异常，建立“同端点覆盖合并、软删除槽位紧凑复用”的数据自愈机制；
- **WS-Discovery 多网卡端口冲突规避**：针对多网卡环境 UDP 3702 监听端口被系统进程占用的场景，引入安全随机端口自适应绑定与单播探测降级；
- **RTP 音频通道泄漏修复**：修复语音广播异常断开时 ZLMediaKit 端口未正确回收的内存与句柄泄漏缺陷。

---

## 🔬 深度排查与机理复盘 (Investigations & Analysis)

- **语音链路抓包深度复盘**：沉淀 [GB28181 语音喊话与对讲故障根因剖析报告](Research-Gb28181-Voice-Talk-And-Broadcast-Failure-Analysis)，通过 Wireshark 真实抓包还原 SIP INVITE / ACK / BYE 全流程时序与音频载荷编码特征；
- **多品牌 IPC 兼容性矩阵报告**：沉淀海康威视、大华股份、宇视科技与雄迈四大主流安防品牌实机联调报告，记录各品牌 RTSP 鉴权与 PTZ 步进特性差异。

---

## ⚠️ 升级指南与兼容性说明 (Migration & Breaking Changes)

### 1. 数据库表结构变更 (无损增量更新)
v2.8.0 引入了全新的 ONVIF 设备管理表，既有用户升级时只需执行增量 SQL，无需清空历史数据：
```sql
-- 执行数据库增量升级脚本 (增量创建 wvp_onvif_device 表)
CREATE TABLE IF NOT EXISTS `wvp_onvif_device` (
    `id` INT AUTO_INCREMENT PRIMARY KEY,
    `device_id` VARCHAR(50) NOT NULL UNIQUE COMMENT 'ONVIF设备唯一标识',
    `name` VARCHAR(255) COMMENT '设备自定义名称',
    `ip` VARCHAR(64) NOT NULL COMMENT '设备IP地址',
    `port` INT NOT NULL DEFAULT 80 COMMENT 'ONVIF服务端口',
    `username` VARCHAR(64) COMMENT '认证用户名',
    `password` VARCHAR(64) COMMENT '认证密码',
    `manufacturer` VARCHAR(128) COMMENT '设备厂商',
    `model` VARCHAR(128) COMMENT '设备型号',
    `firmware_version` VARCHAR(128) COMMENT '固件版本',
    `serial_number` VARCHAR(128) COMMENT '设备序列号',
    `profiles_json` TEXT COMMENT 'Profile与Token元数据缓存',
    `status` INT DEFAULT 1 COMMENT '在线状态: 0-离线, 1-在线',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY `uk_ip_port` (`ip`, `port`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='ONVIF协议设备接入表';
```

### 2. Docker 镜像升级方式
使用 All-in-One 镜像的用户直接拉取最新标签即可完成平滑迁移（数据卷保持挂载）：
```bash
# 1. 拉取最新镜像
docker pull reaticle/wvp-pro-aio:2.8.0

# 2. 重启容器
docker compose -f docker-compose.aio.yml down
docker compose -f docker-compose.aio.yml up -d
```

---

## 📚 关联 Wiki 全景文档 (Documentation Links)

| 文档名称 | 对应模块 | 链接指引 |
| :--- | :--- | :--- |
| **ONVIF 协议支持技术实施方案** | 协议核心 | [ONVIF-Support-Solution](ONVIF-Support-Solution) |
| **ONVIF 五阶段实施工程指南** | 架构交付 | [ONVIF-Implementation-Guide](ONVIF-Implementation-Guide) |
| **All-in-One 镜像合并打包架构方案** | 容器底座 | [Docker-All-in-One-Solution](Docker-All-in-One-Solution) |
| **All-in-One 镜像发布与实施细则** | 部署运维 | [Docker-All-in-One-Implementation](Docker-All-in-One-Implementation) |
| **国标级联通道批量 Excel 导入方案** | 业务扩展 | [Feats-Gb-Cascade-Custom-Channel-Batch-Excel-Solution](Feats-Gb-Cascade-Custom-Channel-Batch-Excel-Solution) |
| **GB28181 语音对讲与广播改造方案** | 业务扩展 | [Feats-Gb28181-Voice-Talk-And-Broadcast-Upgrade-Solution](Feats-Gb28181-Voice-Talk-And-Broadcast-Upgrade-Solution) |
| **语音喊话对讲故障成因研究报告** | 深度排查 | [Research-Gb28181-Voice-Talk-And-Broadcast-Failure-Analysis](Research-Gb28181-Voice-Talk-And-Broadcast-Failure-Analysis) |
| **ONVIF 批量导出覆盖与 ID 复用方案** | 缺陷修复 | [Debug-2026-09-11-Onvif-Batch-Export-Overwrite-And-Id-Reuse-Plan](Debug-2026-09-11-Onvif-Batch-Export-Overwrite-And-Id-Reuse-Plan) |
| **GitHub Wiki 自动化系统分析方案** | 工具链 | [Wiki-Automation-Solution](Wiki-Automation-Solution) |
| **编译与本地开发全指南** | 开发者体验 | [Compile-and-Dev-Guide](Compile-and-Dev-Guide) |
