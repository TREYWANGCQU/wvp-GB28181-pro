#!/bin/bash
# docker/aio/build.sh
set -e

# ==============================================================================
# WVP-PRO All-in-One 多架构镜像自动构建与发布脚本
# ==============================================================================

DOCKER_USER="${DOCKER_USER:-reaticle}"
IMAGE_NAME="${IMAGE_NAME:-wvp-pro-aio}"
VERSION="${VERSION:-2.8.0}"
PLATFORMS="${PLATFORMS:-linux/amd64,linux/arm64}"
ACTION="${1:-push}" # push 或 load

# 定位到仓库根目录
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
cd "${PROJECT_ROOT}"

echo "============================================================"
echo "  开始构建 WVP-PRO All-in-One 镜像 (Multi-Arch: ${PLATFORMS})"
echo "  工程根目录  : ${PROJECT_ROOT}"
echo "  命名空间/包 : ${DOCKER_USER}/${IMAGE_NAME}"
echo "  发布版本号  : ${VERSION} 及 latest"
echo "  执行动作    : ${ACTION}"
echo "============================================================"

# 自动检测构建模式：若上下文中已存在预编译 wvp.jar 与 init.sql，则启用 Dockerfile.fast 极速构建
if [ -z "${DOCKERFILE}" ]; then
    if [ -f "wvp.jar" ] && [ -f "init.sql" ]; then
        DOCKERFILE="docker/aio/Dockerfile.fast"
        echo "[Build] 检测到预编译资产 (wvp.jar & init.sql)，自动启用极速拼装模式: ${DOCKERFILE}"
    else
        DOCKERFILE="docker/aio/Dockerfile"
        echo "[Build] 未检测到预编译资产，启用容器内全源码构建模式: ${DOCKERFILE}"
    fi
fi

# 确保 buildx 实例就绪
if ! docker buildx inspect aio-builder >/dev/null 2>&1; then
    echo "[Buildx] 创建并初始化 aio-builder 多架构构建器..."
    docker buildx create --name aio-builder --driver docker-container --use
    docker buildx inspect --bootstrap
else
    echo "[Buildx] 切换至已有 aio-builder 构建器..."
    docker buildx use aio-builder
fi

# 判断是推送到远程还是本地加载
if [ "$ACTION" = "load" ]; then
    CURRENT_ARCH=$(uname -m)
    case "$CURRENT_ARCH" in
        x86_64)  TARGET_ARCH="linux/amd64" ;;
        aarch64|arm64) TARGET_ARCH="linux/arm64" ;;
        *) TARGET_ARCH="linux/amd64" ;;
    esac
    echo "[Build] 本地冒烟模式：构建 ${TARGET_ARCH} 并加载到本地 Docker Daemon..."
    docker buildx build \
        --platform "${TARGET_ARCH}" \
        -t "${IMAGE_NAME}:test" \
        -f "${DOCKERFILE}" \
        --load \
        .
    echo "[Build] 本地镜像构建并加载完成: ${IMAGE_NAME}:test"
else
    echo "[Build] 生产发布模式：交叉构建 ${PLATFORMS} 并直接推送到 Docker Hub..."
    docker buildx build \
        --platform "${PLATFORMS}" \
        -t "${DOCKER_USER}/${IMAGE_NAME}:${VERSION}" \
        -t "${DOCKER_USER}/${IMAGE_NAME}:latest" \
        -f "${DOCKERFILE}" \
        --push \
        .
    echo "============================================================"
    echo "  多架构镜像构建与推送完成！正在核验远端 Manifest List..."
    echo "============================================================"
    docker buildx imagetools inspect "${DOCKER_USER}/${IMAGE_NAME}:${VERSION}"
fi
