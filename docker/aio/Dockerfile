# docker/aio/Dockerfile

# ==============================================================================
# Stage 1: Web 前端静态编译（利用宿主机原生 CPU 架构加速）
# ==============================================================================
FROM --platform=$BUILDPLATFORM node:20-alpine AS web-builder

WORKDIR /web

# 优先拷贝包依赖清单以利用 Docker 构建缓存
COPY web/package*.json ./
RUN npm install --registry=https://registry.npmmirror.com

COPY web/ ./
ENV NODE_OPTIONS="--openssl-legacy-provider"
RUN npm run build:prod

# ==============================================================================
# Stage 2: Java 后端编译打包（利用宿主机原生 CPU 架构加速）
# ==============================================================================
FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-21-alpine AS wvp-builder

WORKDIR /build
COPY pom.xml .
# 预拉取依赖缓存
RUN mvn dependency:go-offline -B || true

COPY src ./src
# 注入 Stage 1 编译的前端静态资源至 Spring Boot static 目录
COPY --from=web-builder /web/dist ./src/main/resources/static

RUN mvn clean package -DskipTests -B

# ==============================================================================
# Stage 3: 基于目标平台生成微型定制 JRE 21 (依赖 TARGETPLATFORM 保证指令集匹配)
# ==============================================================================
FROM --platform=$TARGETPLATFORM eclipse-temurin:21-jdk-alpine AS jre-builder

RUN echo "[Stage 3] 针对目标架构执行 JRE 21 定制裁剪 (jlink)..." && \
    $JAVA_HOME/bin/jlink \
    --add-modules java.base,java.compiler,java.desktop,java.instrument,java.management,java.naming,java.net.http,java.prefs,java.rmi,java.scripting,java.security.jgss,java.security.sasl,java.sql,java.sql.rowset,java.transaction.xa,java.xml,jdk.crypto.cryptoki,jdk.crypto.ec,jdk.unsupported,jdk.management \
    --strip-debug \
    --no-man-pages \
    --no-header-files \
    --compress=2 \
    --output /opt/java-runtime

# ==============================================================================
# Stage 4: 目标架构编译 ZLMediaKit (针对 Alpine Linux musl libc 优化，使用 3.19 内置 libsrtp 2.5.0)
# ==============================================================================
FROM --platform=$TARGETPLATFORM alpine:3.19 AS zlm-builder

RUN apk update && apk add --no-cache \
    build-base \
    cmake \
    git \
    linux-headers \
    openssl-dev \
    libsrtp-dev \
    pkgconf \
    coreutils && \
    printf '#pragma once\n#include <stddef.h>\n#ifdef __cplusplus\nextern "C" {\n#endif\nstatic inline int backtrace(void **b, int s) { (void)b; (void)s; return 0; }\nstatic inline char **backtrace_symbols(void *const *b, int s) { (void)b; (void)s; return NULL; }\nstatic inline void backtrace_symbols_fd(void *const *b, int s, int f) { (void)b; (void)s; (void)f; }\n#ifdef __cplusplus\n}\n#endif\n' > /usr/include/execinfo.h

WORKDIR /build
# 拉取 ZLMediaKit 核心源码并更新子模块
RUN git clone --depth 1 https://gitee.com/xia-chu/ZLMediaKit.git && \
    cd ZLMediaKit && git submodule update --init --recursive --depth 1

WORKDIR /build/ZLMediaKit/build
# 开启 WebRTC、关闭单元测试、采用 Release 构建并剥离符号 (注: -O2 降阶与 -j2 规避仿真器段错误与 OOM)
RUN cmake .. \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_C_FLAGS="-O2" \
    -DCMAKE_CXX_FLAGS="-O2" \
    -DENABLE_WEBRTC=ON \
    -DENABLE_TESTS=OFF \
    -DENABLE_API=ON \
    -DENABLE_SERVER=ON && \
    cmake --build . --target MediaServer -j2 && \
    strip --strip-all /build/ZLMediaKit/release/linux/Release/MediaServer

# ==============================================================================
# Stage 5: 最终精简生产运行底座 (Alpine Linux 3.19)
# ==============================================================================
FROM --platform=$TARGETPLATFORM alpine:3.19 AS final-runner

LABEL maintainer="reaticle <y.wang@reaticle.com>"
LABEL description="WVP-PRO All-in-One: Java 21 + Vue UI + ZLM + MariaDB + Redis"

ENV TZ=Asia/Shanghai \
    LANG=C.UTF-8 \
    JAVA_HOME=/opt/java-runtime \
    PATH=/opt/java-runtime/bin:/opt/media/bin:$PATH

# 1. 仅安装 Alpine 运行时依赖（剔除任何编译器与开发包）
RUN apk update && apk add --no-cache \
    bash \
    curl \
    tzdata \
    ca-certificates \
    mariadb \
    mariadb-client \
    redis \
    libsrtp \
    ffmpeg \
    libstdc++ \
    libgcc && \
    cp /usr/share/zoneinfo/${TZ} /etc/localtime && \
    echo "${TZ}" > /etc/timezone && \
    sed -i 's/^skip-networking/#skip-networking/' /etc/my.cnf.d/mariadb-server.cnf 2>/dev/null || true && \
    rm -rf /var/cache/apk/* /var/lib/mysql/*

# 2. 建立标准化目录布局
RUN mkdir -p \
    /opt/wvp/config \
    /opt/wvp/templates \
    /opt/wvp/logs \
    /opt/media/bin \
    /opt/media/conf \
    /opt/media/bin/www/record \
    /opt/media/log \
    /var/lib/mysql \
    /var/log/mysql \
    /run/mysqld && \
    chown -R mysql:mysql /var/lib/mysql /run/mysqld /var/log/mysql

# 3. 装配各阶段产物
# 3.1 JRE 21
COPY --from=jre-builder /opt/java-runtime /opt/java-runtime

# 3.2 ZLMediaKit 执行体与静态资源
COPY --from=zlm-builder /build/ZLMediaKit/release/linux/Release/MediaServer /opt/media/bin/MediaServer
COPY --from=zlm-builder /build/ZLMediaKit/release/linux/Release/default.pem /opt/media/bin/default.pem
COPY --from=zlm-builder /build/ZLMediaKit/www/ /opt/media/bin/www/

# 3.3 WVP-PRO Jar 包与数据库脚本（自动合并基础表与 ONVIF 增量表）
COPY --from=wvp-builder /build/target/wvp-pro-*.jar /opt/wvp/wvp.jar
COPY 数据库/2.7.4/初始化-mysql-2.7.4.sql /tmp/init-base.sql
COPY 数据库/2.7.4/增量-onvif.sql /tmp/init-onvif.sql
RUN cat /tmp/init-base.sql > /opt/wvp/init.sql && \
    printf "\n\n-- ==================== ONVIF INCREMENTAL TABLES ====================\n\n" >> /opt/wvp/init.sql && \
    cat /tmp/init-onvif.sql >> /opt/wvp/init.sql && \
    rm -f /tmp/init-*.sql

# 3.4 出厂配置模板与进程监督引擎
COPY docker/aio/conf/application-aio.yml /opt/wvp/templates/application-aio.yml
COPY docker/aio/conf/zlm-config.ini /opt/wvp/templates/zlm-config.ini
COPY docker/aio/conf/redis-aio.conf /opt/wvp/templates/redis-aio.conf
COPY docker/aio/entrypoint.sh /usr/local/bin/entrypoint.sh

# 修正执行权限并防止 Windows CRLF 换行符
RUN sed -i 's/\r$//' /usr/local/bin/entrypoint.sh && \
    chmod +x /usr/local/bin/entrypoint.sh /opt/media/bin/MediaServer

# 核心暴露端口声明
EXPOSE 18080/tcp 8116/tcp 8116/udp 3702/udp 9092/tcp 8000/udp 8000/tcp 1935/tcp 554/tcp 30000-30050/udp 30000-30050/tcp

VOLUME ["/opt/wvp/config", "/opt/media/conf", "/var/lib/mysql", "/opt/media/bin/www/record", "/opt/wvp/logs"]

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
