#!/bin/sh
# docker/aio/entrypoint.sh
set -e

echo "=========================================================="
echo "  WVP-PRO All-in-One Container Supervision Engine v2.7.4  "
echo "=========================================================="

# -------------------------------------------------------------
# 1. POSIX 信号拦截与级联逆序优雅退出逻辑
# -------------------------------------------------------------
stop_services() {
    echo ""
    echo "[Supervision] 收到容器停止信号 (SIGTERM/SIGINT)，执行逆序优雅停机..."
    
    # 步骤 1.1 停止 WVP-PRO (Java 业务层)
    if [ -n "$WVP_PID" ] && kill -0 "$WVP_PID" 2>/dev/null; then
        echo "[Shutdown] 1/4 正在停止 WVP-PRO 信令服务 (PID: $WVP_PID)..."
        kill -TERM "$WVP_PID" 2>/dev/null
        wait "$WVP_PID" 2>/dev/null || true
        echo "[Shutdown] WVP-PRO 已安全退出。"
    fi

    # 步骤 1.2 停止 ZLMediaKit (流媒体层)
    if [ -n "$ZLM_PID" ] && kill -0 "$ZLM_PID" 2>/dev/null; then
        echo "[Shutdown] 2/4 正在停止 ZLMediaKit 流媒体引擎 (PID: $ZLM_PID)..."
        kill -TERM "$ZLM_PID" 2>/dev/null
        wait "$ZLM_PID" 2>/dev/null || true
        echo "[Shutdown] ZLMediaKit 已安全退出。"
    fi

    # 步骤 1.3 停止 Redis
    echo "[Shutdown] 3/4 正在停止 Redis 缓存服务..."
    redis-cli -h 127.0.0.1 -p 6379 shutdown 2>/dev/null || true
    echo "[Shutdown] Redis 已安全退出。"

    # 步骤 1.4 安全刷新 MariaDB 脏页并停机 (防止 ibdata 损坏)
    echo "[Shutdown] 4/4 正在执行 MariaDB 脏页刷盘与平滑停机..."
    mysqladmin --socket=/run/mysqld/mysqld.sock shutdown 2>/dev/null || true
    echo "[Shutdown] MariaDB 已安全退出。"

    echo "[Supervision] 全组件优雅退出完毕，容器安全终止。"
    exit 0
}

# 注册信号捕获
trap stop_services SIGTERM SIGINT

# -------------------------------------------------------------
# 2. 外部映射配置自愈防御 (Config Self-Healing)
# -------------------------------------------------------------
mkdir -p /opt/wvp/config /opt/media/conf /opt/wvp/logs /opt/media/log /var/log/mysql /run/mysqld
chown -R mysql:mysql /run/mysqld /var/log/mysql

if [ ! -f /opt/wvp/config/application.yml ]; then
    echo "[Self-Healing] 宿主未挂载 application.yml，自动注入出厂默认配置..."
    cp /opt/wvp/templates/application-aio.yml /opt/wvp/config/application.yml
fi

if [ ! -f /opt/media/conf/config.ini ]; then
    echo "[Self-Healing] 宿主未挂载 config.ini，自动注入出厂 ZLM 默认配置..."
    cp /opt/wvp/templates/zlm-config.ini /opt/media/conf/config.ini
fi

if [ ! -f /etc/redis.conf ]; then
    echo "[Self-Healing] 宿主未挂载 redis.conf，自动注入出厂 Redis 配置..."
    cp /opt/wvp/templates/redis-aio.conf /etc/redis.conf
fi

# -------------------------------------------------------------
# 3. MariaDB 存储初始化与表名大小写合规建表
# -------------------------------------------------------------
if [ ! -d "/var/lib/mysql/mysql" ]; then
    echo "[DB-Init] 检测到数据目录为空，执行 MariaDB 首次初始化 (--lower-case-table-names=1)..."
    chown -R mysql:mysql /var/lib/mysql
    mysql_install_db --user=mysql --datadir=/var/lib/mysql --lower-case-table-names=1 >/dev/null 2>&1

    echo "[DB-Init] 启动临时 mysqld 灌入初始化库表结构 (init.sql)..."
    /usr/bin/mysqld --user=mysql --datadir=/var/lib/mysql --bootstrap --lower-case-table-names=1 <<EOF
FLUSH PRIVILEGES;
CREATE DATABASE IF NOT EXISTS \`wvp\` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
ALTER USER 'root'@'localhost' IDENTIFIED VIA mysql_native_password USING PASSWORD('');
GRANT ALL PRIVILEGES ON *.* TO 'root'@'localhost' WITH GRANT OPTION;
CREATE USER IF NOT EXISTS 'root'@'127.0.0.1' IDENTIFIED VIA mysql_native_password USING PASSWORD('');
GRANT ALL PRIVILEGES ON *.* TO 'root'@'127.0.0.1' WITH GRANT OPTION;
USE \`wvp\`;
SOURCE /opt/wvp/init.sql;
FLUSH PRIVILEGES;
EOF
    echo "[DB-Init] 数据库初始化完成并赋予本地无密直连权限。"
fi

# -------------------------------------------------------------
# 4. 有序拉起各组件服务
# -------------------------------------------------------------

# 4.1 启动 Redis
echo "[Startup] 1/4 启动 Redis 缓存引擎..."
redis-server /etc/redis.conf --daemonize yes

# 4.2 启动 MariaDB
echo "[Startup] 2/4 启动 MariaDB 数据库引擎..."
/usr/bin/mysqld_safe --user=mysql --datadir=/var/lib/mysql --lower-case-table-names=1 \
    --socket=/run/mysqld/mysqld.sock --log-error=/var/log/mysql/error.log >/dev/null 2>&1 &

# 等待 MariaDB 套接字就绪 (最多等待 20 秒)
WAIT_COUNT=0
until mysqladmin --socket=/run/mysqld/mysqld.sock ping --silent >/dev/null 2>&1 || [ $WAIT_COUNT -ge 20 ]; do
    sleep 1
    WAIT_COUNT=$((WAIT_COUNT + 1))
done
if [ $WAIT_COUNT -ge 20 ]; then
    echo "[Error] MariaDB 启动超时，请检查 /var/log/mysql/error.log"
    exit 1
fi
echo "[Startup] MariaDB 就绪。"

# 4.3 启动 ZLMediaKit
echo "[Startup] 3/4 启动 ZLMediaKit 流媒体引擎..."
/opt/media/bin/MediaServer -c /opt/media/conf/config.ini -d &
ZLM_PID=$!

# 等待 ZLM 就绪
sleep 2

# 4.4 启动 WVP-PRO
echo "[Startup] 4/4 启动 WVP-PRO 国标信令平台..."
/opt/java-runtime/bin/java \
    -Djava.security.egd=file:/dev/./urandom \
    -Xms256m \
    -Xmx512m \
    -XX:+UseG1GC \
    -jar /opt/wvp/wvp.jar \
    --spring.config.location=/opt/wvp/config/application.yml &
WVP_PID=$!

echo "=========================================================="
echo "  WVP-PRO All-in-One 全套组件启动就绪！                     "
echo "  - Web 控制台 : http://<Host-IP>:18080                   "
echo "  - SIP 国标端口: 8116 (UDP/TCP)                           "
echo "  - ONVIF 搜寻 : 3702 (UDP)                               "
echo "  - WebRTC 对讲: 8000 (UDP)                               "
echo "=========================================================="

# -------------------------------------------------------------
# 5. 常驻阻塞主进程，守护等待 WVP 退出
# -------------------------------------------------------------
wait "$WVP_PID"
