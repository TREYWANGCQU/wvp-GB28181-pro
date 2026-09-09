#!/usr/bin/env bash
# docker/infra/package-images.sh
# ==============================================================================
# 配合机 (iMac 26.1 / 联网主机) 专职 Docker 镜像拉取与离线打包脚本
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUTPUT_FILE="${SCRIPT_DIR}/wvp-infra-images.tar.gz"

echo "==> [1/3] 正在联网拉取官方认证基础镜像 (显式指定 linux/amd64 架构)..."
docker pull --platform linux/amd64 mysql:8.0
docker pull --platform linux/amd64 redis:7.0
docker pull --platform linux/amd64 zlmediakit/zlmediakit:master

echo "==> [2/3] 正在合并镜像并执行 gzip 高压缩打包..."
docker save mysql:8.0 redis:7.0 zlmediakit/zlmediakit:master | gzip > "${OUTPUT_FILE}"

echo "==> [3/3] 打包完成！"
ls -lh "${OUTPUT_FILE}"
echo "==> 请将 ${OUTPUT_FILE} 与 docker/infra 编排目录传输至离线测试机。"
