<!-- docker/infra/README.md -->
# WVP-PRO 基础设施离线编排环境 (Infrastructure)

本目录承载 WVP-PRO 研发体系中所需的全套核心中间件容器配置，适用于 **“配合机 (iMac 26.1) 联网打包 -> 离线测试机 (Linux 192.168.30.x) 运行 -> 主开发机 (Win11 192.168.30.252) 直连调试”** 的协同开发架构。

整合服务包括：
1. **MySQL 8.0**：自动挂载 `conf.d/my.cnf`（已配置 `skip-name-resolve` 禁用反向域名解析，根除代理连接阻断），首次启动自动执行 `initdb/01-init.sql`（2.7.4 基础全量表）与 `initdb/02-onvif.sql`（ONVIF 协议增量表）。
2. **Redis 7.0**：自动挂载 `redis/redis.conf`，提供高频会话与信令锁缓存。
3. **ZLMediaKit (master)**：流媒体核心引擎，已映射 HTTP (9092/80)、RTMP (1935)、RTSP (554)、WebRTC (8000/udp) 及 GB28181 RTP 收流端口段 (`40000-40050`)。

---

## 协同工作流与操作指南

### 1. 配合机（iMac / 联网机）一键拉取与镜像打包导出
在有外网条件的构建机上执行：
```bash
# 联网拉取全套镜像（显式指定 linux/amd64 架构，避免 Apple Silicon Mac 默认拉取 arm64）
docker pull --platform linux/amd64 mysql:8.0
docker pull --platform linux/amd64 redis:7.0
docker pull --platform linux/amd64 zlmediakit/zlmediakit:master

# 合并打包并 gzip 压缩导出（约 500~600MB）
docker save --platform linux/amd64 mysql:8.0 redis:7.0 zlmediakit/zlmediakit:master | gzip > wvp-infra-images.tar.gz
```
*(或直接执行配套脚本 `./package-images.sh`)*

### 2. 将镜像包与本目录分发至离线测试机
```powershell
# 在 Windows 开发机或中转机上执行 SCP 推送
scp D:\wvp-infra-images.tar.gz root@192.168.30.100:/tmp/
scp -r docker\infra root@192.168.30.100:~/wvp-infra
```

### 3. 离线测试机（Linux）加载镜像并一键拉起
登录 192.168.30.x 测试机（无需外网）：
```bash
# 1. 离线载入镜像
docker load < /tmp/wvp-infra-images.tar.gz

# 2. 进入编排目录并拉起服务
cd ~/wvp-infra
docker compose up -d

# 3. 检查容器健康状态与端口监听
docker compose ps
```
*(或直接执行配套脚本 `./load-and-run.sh`)*

---

## 常用维护操作

### 查看实时日志
```bash
# 查看 ZLM 流媒体日志
docker compose logs -f wvp-zlm

# 查看 MySQL 初始执行日志
docker compose logs -f wvp-mysql
```

### 增量热更新 ONVIF 数据库表（保留现有数据时）
若测试机已有历史 MySQL 数据卷而未重新初始化：
```bash
docker exec -i wvp-mysql mysql -uroot -proot wvp < mysql/initdb/02-onvif.sql
```

### 停止并清理服务
```bash
# 停止容器（保留数据卷）
docker compose down

# 彻底重置（包含清除数据库卷）
docker compose down -v
```

---

## 端口与网络契约

| 服务 | 容器内端口 | 宿主机映射端口 | 协议 | 说明 |
|---|---|---|---|---|
| MySQL 8.0 | 3306 | 3306 | TCP | 后端持久化直连 (`skip-name-resolve`) |
| Redis 7.0 | 6379 | 6379 | TCP | 事务缓存与 Session 锁 |
| ZLM HTTP | 80 | 9092 | TCP | RESTful 控制 API 与 HTTP-FLV/HLS 播放 |
| ZLM WebRTC | 8000 | 8000 | UDP | 语音对讲与低延迟点播核心端口 |
| ZLM RTSP | 554 | 554 | TCP/UDP | RTSP 代理与推拉流 |
| ZLM RTMP | 1935 | 1935 | TCP/UDP | RTMP 推拉流 |
| ZLM RTP Proxy | 40000~40050 | 40000~40050 | UDP/TCP | GB28181 国标收流端口段（与 WVP 配置对齐） |
