#!/bin/sh
# docker/aio/setup-workspace.sh
set -e

# ==============================================================================
# WVP-PRO All-in-One 宿主机外挂持久化工作空间一键初始化脚本
# ==============================================================================

TARGET_DIR="${1:-./wvp-aio-data}"

echo "=========================================================="
echo "  初始化 WVP All-in-One 宿主机挂载目录: ${TARGET_DIR}"
echo "=========================================================="

mkdir -p "${TARGET_DIR}/config"
mkdir -p "${TARGET_DIR}/data/mysql"
mkdir -p "${TARGET_DIR}/data/record"
mkdir -p "${TARGET_DIR}/logs/wvp"
mkdir -p "${TARGET_DIR}/logs/media"
mkdir -p "${TARGET_DIR}/logs/mysql"

# 针对 Linux 环境下的 MariaDB (UID 100 左右或 mysql 用户) 与普通权限初始化
if [ "$(id -u)" = "0" ]; then
    echo "[Setup] 正在修正 Linux 下数据目录属主权限..."
    # Alpine mariadb 默认 uid:gid 为 mysql:mysql (通常为 100:101 或 999:999)
    chown -R 100:101 "${TARGET_DIR}/data/mysql" "${TARGET_DIR}/logs/mysql" 2>/dev/null || true
fi

echo "[Setup] 正在生成核心配置文件出厂模板到挂载目录..."

# 防御性自愈：若之前因 Docker 容器启动未预置导致将配置文件误创建为目录，则予以清理
for cfg in application.yml zlm.ini redis.conf; do
    if [ -d "${TARGET_DIR}/config/${cfg}" ]; then
        echo "[Warn] 检测到 ${TARGET_DIR}/config/${cfg} 为目录（可能由 Docker 挂载未预置引发），正在清理错误目录..."
        rm -rf "${TARGET_DIR}/config/${cfg}"
    fi
done

# 1. 生成 WVP 核心闭环配置
if [ ! -f "${TARGET_DIR}/config/application.yml" ]; then
    echo "[Setup] 写入 ${TARGET_DIR}/config/application.yml..."
    cat << 'EOF' > "${TARGET_DIR}/config/application.yml"
# docker/aio/conf/application-aio.yml
server:
  port: 18080

spring:
  application:
    name: wvp-pro-aio
  profiles:
    active: aio
  data:
    redis:
      # 闭环直连本容器内置 Redis
      host: 127.0.0.1
      port: 6379
      password: ""
      database: 0
  datasource:
    # 闭环直连本容器内置 MariaDB
    url: jdbc:mysql://127.0.0.1:3306/wvp?useUnicode=true&characterEncoding=UTF8&rewriteBatchedStatements=true&serverTimezone=Asia/Shanghai&useSSL=false&allowMultiQueries=true&allowPublicKeyRetrieval=true
    username: root
    password: ""
    driver-class-name: com.mysql.cj.jdbc.Driver

sip:
  # 容器暴露的 SIP 国标信令接入端口
  port: 8116
  # 若容器运行在 Bridge 模式下，推流摄像头需填写宿主机外部 IP；宿主机外部 IP 也可在启动时通过环境变量覆盖
  ip: 0.0.0.0
  id: 41010500002000000001
  domain: 4101050000
  password: admin

media:
  id: zlmediakit-aio
  # 容器内部闭环通信：WVP -> ZLM
  ip: 127.0.0.1
  http-port: 9092
  # 容器内部闭环通信：ZLM -> WVP (闭环通信规避宿主防火墙阻断)
  hook-ip: 127.0.0.1
  # [必配/按需修改] 国标设备点播推流的目标宿主机 IP（不可为 127.0.0.1，必须为设备可路由访问的宿主机 IP）
  sdp-ip: 127.0.0.1
  # [必配/按需修改] 客户端浏览器播放拉流的宿主机 IP（不可为 127.0.0.1，必须为客户端可访问的宿主机 IP）
  stream-ip: 127.0.0.1
  secret: AzmbJcNEu3wJUPImx72ckPSxCzQ27HEX
  auto-config: true
  rtp:
    enable: true
    port-range: 30000,30500
    send-port-range: 30000,30500

logging:
  file:
    path: /opt/wvp/logs
  level:
    root: INFO
    com.genersoft.wvp: INFO
EOF
else
    echo "[Setup] ${TARGET_DIR}/config/application.yml 已存在，跳过覆盖。"
fi

# 2. 生成 ZLMediaKit 流媒体与 WebRTC 配置
if [ ! -f "${TARGET_DIR}/config/zlm.ini" ]; then
    echo "[Setup] 写入 ${TARGET_DIR}/config/zlm.ini..."
    cat << 'EOF' > "${TARGET_DIR}/config/zlm.ini"
# docker/aio/conf/zlm-config.ini
[api]
apiDebug=0
secret=AzmbJcNEu3wJUPImx72ckPSxCzQ27HEX
snapRoot=./www/snap/
defaultSnap=./www/logo.png

[general]
mediaServerId=zlmediakit-aio
enableVhost=0

[http]
port=9092
sslport=9443
rootPath=./www

[rtc]
# WebRTC 媒体与双向对讲端口 (必须暴露 UDP 8000)
port=8000
tcpPort=8000

[rtp_proxy]
# 国标 RTP 收流端口池
port=10000
port_range=30000-30500

[hook]
enable=1
on_flow_report=http://127.0.0.1:18080/index/hook/on_flow_report
on_http_access=http://127.0.0.1:18080/index/hook/on_http_access
on_play=http://127.0.0.1:18080/index/hook/on_play
on_publish=http://127.0.0.1:18080/index/hook/on_publish
on_record_mp4=http://127.0.0.1:18080/index/hook/on_record_mp4
on_record_ts=http://127.0.0.1:18080/index/hook/on_record_ts
on_rtsp_auth=http://127.0.0.1:18080/index/hook/on_rtsp_auth
on_rtsp_realm=http://127.0.0.1:18080/index/hook/on_rtsp_realm
on_shell_login=http://127.0.0.1:18080/index/hook/on_shell_login
on_stream_changed=http://127.0.0.1:18080/index/hook/on_stream_changed
on_stream_none_reader=http://127.0.0.1:18080/index/hook/on_stream_none_reader
on_stream_not_found=http://127.0.0.1:18080/index/hook/on_stream_not_found
on_server_started=http://127.0.0.1:18080/index/hook/on_server_started
on_server_keepalive=http://127.0.0.1:18080/index/hook/on_server_keepalive
on_send_rtp_stopped=http://127.0.0.1:18080/index/hook/on_send_rtp_stopped
EOF
else
    echo "[Setup] ${TARGET_DIR}/config/zlm.ini 已存在，跳过覆盖。"
fi

# 3. 生成 Redis 基础配置
if [ ! -f "${TARGET_DIR}/config/redis.conf" ]; then
    echo "[Setup] 写入 ${TARGET_DIR}/config/redis.conf..."
    cat << 'EOF' > "${TARGET_DIR}/config/redis.conf"
# docker/aio/conf/redis-aio.conf
bind 127.0.0.1
protected-mode yes
port 6379
tcp-backlog 511
timeout 0
tcp-keepalive 300
daemonize no
pidfile /run/redis.pid
loglevel notice
logfile ""
databases 16
maxmemory 128mb
maxmemory-policy allkeys-lru
appendonly no
save ""
EOF
else
    echo "[Setup] ${TARGET_DIR}/config/redis.conf 已存在，跳过覆盖。"
fi

echo "[Setup] 挂载工作区初始化完毕！结构如下："
ls -la "${TARGET_DIR}"
