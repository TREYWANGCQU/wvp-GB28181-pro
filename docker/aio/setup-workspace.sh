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

echo "[Setup] 正在复制核心配置文件出厂模板到挂载目录..."
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
if [ -d "${SCRIPT_DIR}/conf" ]; then
    cp -n "${SCRIPT_DIR}/conf/application-aio.yml" "${TARGET_DIR}/config/application.yml" 2>/dev/null || true
    cp -n "${SCRIPT_DIR}/conf/zlm-config.ini" "${TARGET_DIR}/config/zlm.ini" 2>/dev/null || true
    cp -n "${SCRIPT_DIR}/conf/redis-aio.conf" "${TARGET_DIR}/config/redis.conf" 2>/dev/null || true
fi

echo "[Setup] 挂载工作区初始化完毕！结构如下："
ls -la "${TARGET_DIR}"
