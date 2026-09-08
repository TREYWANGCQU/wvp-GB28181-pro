<!-- docker/infra/README.md -->
# WVP-PRO 本地/辅助机调试环境 (Infrastructure)

本目录为 WVP-PRO 调试开发所需的中间件编排配置，整合了：
1. **MySQL 8.0**：自动挂载 `conf.d/my.cnf`，首次启动自动导入 `initdb/01-init.sql`（基础全量表）与 `initdb/02-onvif.sql`（ONVIF 协议增量表）。
2. **Redis 7.0**：自动挂载 `redis/redis.conf`。
3. **ZLMediaKit (master)**：流媒体服务，已配置 HTTP (9092/80)、RTMP (1935)、RTSP (554)、WebRTC (8000/udp)、RTP 国标收流端口段 (40000-40050)。

---

## 常用操作

### 1. 启动服务
```bash
docker compose up -d
```

### 2. 查看容器状态
```bash
docker compose ps
```

### 3. 查看 ZLM 日志
```bash
docker compose logs -f wvp-zlm
```

### 4. 停止并释放容器
```bash
docker compose down
```

### 5. 增量热升级 ONVIF 数据库表（已有 MySQL 卷时无需销毁）
```bash
docker exec -i wvp-mysql mysql -uroot -proot wvp < mysql/initdb/02-onvif.sql
```
