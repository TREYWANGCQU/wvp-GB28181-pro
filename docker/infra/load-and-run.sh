#!/usr/bin/env bash
# docker/infra/load-and-run.sh
# ==============================================================================
# 离线测试机 (Linux 192.168.30.x) 镜像离线加载与一键拉起服务脚本
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PACKAGE_PATH="${1:-/tmp/wvp-infra-images.tar.gz}"

if [ ! -f "${PACKAGE_PATH}" ]; then
  if [ -f "${SCRIPT_DIR}/wvp-infra-images.tar.gz" ]; then
    PACKAGE_PATH="${SCRIPT_DIR}/wvp-infra-images.tar.gz"
  else
    echo "错误: 未找到镜像压缩包 ${PACKAGE_PATH}。"
    echo "用法: $0 [镜像压缩包路径，默认为 /tmp/wvp-infra-images.tar.gz]"
    exit 1
  fi
fi

echo "==> [1/3] 正在从 ${PACKAGE_PATH} 离线载入 Docker 镜像..."
docker load < "${PACKAGE_PATH}"

echo "==> [2/3] 验证本地载入的镜像..."
docker images | grep -E 'mysql|redis|zlmediakit'

echo "==> [3/3] 正在后台拉起基础设施容器..."
cd "${SCRIPT_DIR}"
docker compose up -d

echo "==> 服务启动状态："
docker compose ps
