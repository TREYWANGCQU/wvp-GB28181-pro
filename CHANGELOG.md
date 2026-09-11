<!-- CHANGELOG.md -->
# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [2.8.0] - 2026-09-11

### Added (新增特性)
- **ONVIF 原生协议栈全生命周期闭环**：
  - 自研轻量级 SOAP 1.2 通信引擎（基于 JDK 21 原生 HttpClient 与 dom4j，零引入外部笨重依赖）。
  - 全网 WS-Discovery 局域网探测器（支持 UDP 3702 多播广播与单播探测）。
  - 动态时钟偏斜补偿算法（毫秒级自适应设备端时间漂移，消除 WS-Security 401 鉴权故障）。
  - 数据库增量扩展 `wvp_onvif_device` 表，自动化 Profile S/T 媒体流 Token 解析与主/子码流映射。
  - ZLMediaKit StreamProxy 流媒体代理接管，支持空闲断流守护与断线平滑重连。
  - 云台 PTZ 空间坐标归一化模型（$[-1.0, 1.0]$ 速度映射、八向无极控制、预置位检索与调用）。
  - Vue 2 响应式 Web 控制台 ONVIF 设备管理、一键发现弹窗与虚拟云台控制面板。
- **Docker All-in-One 四合一极简容器交付体系**：
  - 生产级多合一单镜像，集成 WVP-PRO、ZLMediaKit、MariaDB 与 Redis，镜像体积瘦身至约 300MB。
  - 支持 `linux/amd64` 与 `linux/arm64` 双架构跨平台原生适配与 Manifest List 发布。
  - 集成 supervisord 进程守护，提供容器自愈重启、健康探针与平滑退出清理。
  - 支持通过 `${WVP_VERSION:-2.8.0}` 环境变量热注入实现多版本灵活拉取。
- **国标级联自定义通道编码批量 Excel 导入/导出**：
  - 基于 EasyExcel 框架构建流式批量通道导入与导出。
  - 提供 20 位国标编码规范校验、同级目录重名检测与冲突防御机制。
- **自动化运维与知识库生态**：
  - 新增 `wiki-curator` 与 `release-curator` 本地发版与编目技能。
  - 新增版本联动提升脚本 `scripts/bump-version.ps1`，确保根版本单一信任源。

### Changed (特性变更与重构)
- **GB28181 语音对讲与广播信令解耦**：
  - 彻底拆解双向语音对讲 (Voice Intercom / Talk) 与单向语音广播 (Voice Broadcast) 的信令流程。
  - 修正 SDP 音频媒体属性协商（`sendonly` / `recvonly` 反转问题），解决媒体回环与音频无声故障。
- **构建系统与工程脚本统一**：
  - 规范 Maven `pom.xml`、Docker Compose 与编译脚本的版本标识，全面升级为 `2.8.0`。

### Fixed (缺陷修复与稳定性)
- **ONVIF 批量导出与主键冲突**：修复设备同 IP:Port 覆盖更新时的主键冲突异常，支持软删除槽位紧凑复用。
- **WS-Discovery 多网卡冲突**：修复在多网卡宿主机上 UDP 3702 端口被占用时探测器启动失败的问题，支持安全随机端口回退。
- **RTP 音频通道泄漏**：修复语音广播异常断开时未及时通知 ZLMediaKit 回收端口与会话的资源泄漏缺陷。

---

## [2.7.4] - 历史基线版本
- 支持 GB28181-2016 / 2022 基础国标视频接入与流媒体分发。
- 支持微服务与基础容器化部署模式。
