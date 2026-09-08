<!-- doc/reaticle_docs/docker-all-in-one-solution.md -->

# WVP-PRO All-in-One 镜像合并打包与极速发布架构方案

## 0 事实、研判与假设分流清单 (Fact, Judgment & Speculation)

为确保工程落地的确定性，避免隐性技术债务，对方案涉及的技术要素进行事实与假设分流：

### 0.1 已验证事实 (Fact)
1. **源码与运行时依赖**：当前项目后端基于 Spring Boot 3.4.4，编译与运行必须依赖 JDK 21（[pom.xml](../../pom.xml#L63)）；前端为 Vue CLI 4 项目，构建依赖 Node.js 且需放开 OpenSSL 3.0 兼容参数（[doc/_content/introduction/compile.md](../../doc/_content/introduction/compile.md#L309)）。
2. **数据源支持现状**：后端已集成 `mysql-connector-j:8.2.0` 驱动（[pom.xml](../../pom.xml#L168-L172)），原生完全兼容 MySQL / MariaDB 协议；未集成 SQLite JDBC 驱动。
3. **现有 Dockerfile 分布**：目前仓库在 `docker/` 下为分立构建模式（wvp、media、mysql、redis、nginx 分别打包），尚未提供全组件一体化单镜像（[docker/docker-compose.yml](../../docker/docker-compose.yml)）。
4. **编译宿主机环境**：构建配合机为 iMac 26.1（macOS 环境，搭载 Colima Docker Daemon），宿主架构可能为 Apple Silicon（arm64）或 Intel（x86_64）。

### 0.2 工程研判 (Judgment)
1. **一体化单镜像数据库引擎选型**：
   - 确定**唯一采用 Alpine 原生 MariaDB 作为内置轻量 SQL 引擎**。
   - Alpine `mariadb` 与 `mariadb-client` 安装包仅增加 ~18MB 压缩体积，完全兼容现有 MySQL 8.0 语法与全套建表脚本（[数据库/2.7.4/初始化-mysql-2.7.4.sql](../../数据库/2.7.4/初始化-mysql-2.7.4.sql)），工程师无需改变任何 SQL 操作习惯或维护两套方言脚本。
2. **进程管理与信号级联守护**：
   - 单容器内需常驻运行 MariaDB、Redis、ZLMediaKit 与 Java (WVP)。禁止让 Java 进程直接执行 `exec` 替换 PID 1，否则容器停机时内核将向后台的 MariaDB 和 ZLM 下发 `SIGKILL`，导致数据库未刷盘崩溃与切片损坏。
   - 采用 **POSIX Shell 级守护引擎**（配合 `trap` 捕获 `SIGTERM`/`SIGINT`），按逆序执行 `WVP` $\rightarrow$ `ZLMediaKit` $\rightarrow$ `Redis` $\rightarrow$ `MariaDB` 优雅停机与僵尸进程回收。
3. **前后端合并策略**：Vue 构建产物直接固化至 WVP 后端的 `src/main/resources/static` 目录中，由 Spring Boot 内置 Tomcat 直接提供静态 Web 托管，**完全剔除 Nginx 容器/进程**，立减 ~25MB 镜像体积与端口转发层级。
4. **iMac 交叉编译加速研判**：
   - 前端 Web 构建（Node.js）与 Java 后端构建（Maven）产物均为平台无关字节码/静态资源，必须在 Dockerfile 中声明 `--platform=$BUILDPLATFORM` 强制利用 iMac 原生硬件加速，严禁通过 QEMU 模拟跨架构编译 Java/Node，规避构建耗时爆炸风险。

### 0.3 未验证假设 (Speculation)
- *[假设 1]*：目标工程师使用环境以 Linux x86_64 (amd64) 与 Linux arm64 (aarch64) 为主。iMac 编译发布时必须利用 `docker buildx` 进行多架构交叉构建与推送。
- *[假设 2]*：单容器对外发布时，生产环境优先推荐使用 `--net=host` 模式以规避 Docker NAT 转发对 WebRTC UDP 与 GB28181 RTP 大范围动态端口段的性能损耗；若在 macOS/Windows 上体验，则通过暴露指定核心端口段运行。

---

## 1 Objectives (核心目标体系)

### 1.1 核心业务与交付目标 (Objective 1)
1. **全栈四合一融合**：将 `WVP-PRO (Java 21 + Vue UI)`、`ZLMediaKit (C++20)`、`Redis (缓存与锁)`、`Alpine MariaDB (MySQL兼容数据库)` 深度整合进**单个 Docker 镜像**。
2. **iMac 极速多架构编译与 Hub 发布**：基于 iMac 配合机（macOS + Colima Docker），依托 `docker buildx` 原生加速模式交叉编译出兼容 `linux/amd64` 与 `linux/arm64` 的双平台镜像，并自动打标签推送至 Docker Hub。
3. **核心配置完全外部映射 (Zero-Code Quick-Start)**：将 WVP 配置（`application.yml`）、ZLMediaKit 配置（`config.ini`）、Redis 配置（`redis.conf`）、数据库存储目录（`/var/lib/mysql`）及录像切片目录（`/opt/media/bin/www/record`）完全外挂暴露，容器销毁而数据与配置不丢。

### 1.2 极限包体压缩目标 (Objective 2)
1. **基准大小门禁**：
   - 传统分别拉取官方镜像总体积：MySQL 8 (~600MB) + OpenJDK 21 (~500MB) + ZLM (~300MB) + Redis (~140MB) = **> 1.5 GB**。
   - **本方案压缩目标**：合并后 Docker 镜像压缩包（Compressed Download Size）控制在 **< 280 MB**，解压后运行时镜像控制在 **< 650 MB**。
2. **多层脱水压缩策略**：
   - **基础底座**：采用 `alpine:3.20` 作为统一运行时底座。
   - **JRE 深度裁剪 (`jlink`)**：利用 JDK 21 自带的 `jlink` 工具，针对 WVP 所需的 Java 模块进行定制化剥离（移除 GUI、国际化编码包等），将 300MB+ 的完整 JRE 压缩至 **~45 MB**。
   - **C++ 符号剥离 (Strip)**：对 ZLMediaKit 编译产物执行 `strip --strip-all MediaServer`，剔除全部调试符号与冗余段。
   - **构建缓存终结**：多阶段构建（Multi-stage Build），中间编译工具链（Maven、GCC、CMake、Node.js、npm 缓存）彻底隔离在构建镜像中，不带入最终发布层。

---

## 2 Constraints & Boundary Contract Matrix (硬性约束与边界契约矩阵)

在单镜像内承载全套多媒体与信令网关，必须严格处理异构运行时及跨层网络边界：

| 交互维度 | 涉及组件 / 契约 | 规范 / RFC 依据 | 边界与隔离控制硬性约束 |
|---|---|---|---|
| **信令协议** | 国标摄像头 $\leftrightarrow$ WVP | GB/T 28181-2016 / 2022<br>RFC 3261 (SIP) | 默认端口 `8116/5060` (UDP/TCP)。容器内必须允许 SIP 重复注册探测与 NAT 穿透。 |
| **媒体交互** | 国标摄像头 $\leftrightarrow$ ZLM | RFC 3550 (RTP/RTCP)<br>RFC 4571 (RTP over TCP) | RTP 收流端口池（如 `30000-30050` UDP/TCP）。若采用 Bridge 网络，映射端口过多会使 iptables 严重膨胀，**生产强制推荐 `--net=host`**。 |
| **实时对讲** | Web 浏览器 $\leftrightarrow$ ZLM | RFC 8829 (JSEP / WebRTC)<br>RFC 8835 (WebRTC Media) | WebRTC 协商端口 `8000/udp`。浏览器强制要求 HTTPS 或 `localhost` 安全上下文才允许打开麦克风。 |
| **内部事件回调** | ZLM $\rightarrow$ WVP | HTTP REST / Webhook 契约 | 单容器内闭环通信：ZLM `hook` 目标地址严格固定为 `http://127.0.0.1:18080`，从物理上消除跨机 IP 配置失步问题。 |
| **进程与信号** | 容器 Entrypoint $\rightarrow$ 子进程 | POSIX.1-2017 Signals | Entrypoint 进程（PID 1）必须拦截 `SIGTERM`/`SIGINT`，**按序级联终止 WVP $\rightarrow$ ZLM $\rightarrow$ Redis $\rightarrow$ MariaDB**，严禁直接 `exec` 导致数据库被 `SIGKILL` 强杀。 |
| **OS 底层构建** | macOS (Colima) $\leftrightarrow$ Linux | OCI Image Specification<br>QEMU binfmt-misc | 避免在 QEMU 下进行全量 Node/Java 构建。**架构无关阶段强制使用 `--platform=$BUILDPLATFORM`**，仅 C++ 编译与最终运行层匹配 `$TARGETPLATFORM`。 |

---

## 3 Architecture (系统与镜像架构设计)

### 3.1 运行时单容器内部架构拓扑

```
+---------------------------------------------------------------------------------------------------+
| Docker Container: [wvp-pro-all-in-one:latest]  (Base: Alpine Linux 3.20)                           |
|                                                                                                   |
|   +-------------------------------------------------------------------------------------------+   |
|   | PID 1: Entrypoint Supervision Engine (POSIX Trap / Graceful Shutdown Controller)          |   |
|   +----+-------------------------+----------------------------+--------------------------+----+   |
|        |                         |                            |                          |        |
|        v                         v                            v                          v        |
|   +------------+           +------------+              +--------------+           +--------------+|
|   |  MariaDB   |           |   Redis    |              |  ZLMediaKit  |           |   WVP-PRO    ||
|   | (10.11/11) |           |  (server)  |              | (MediaServer)|           | (Spring Boot)||
|   +-----+------+           +-----+------+              +------+-------+           +-------+------+|
|         |                        |                            |                           |       |
|         | 127.0.0.1:3306         | 127.0.0.1:6379             | 127.0.0.1:9092 (REST)     |       |
|         +------------------------+----------------------------+---------------------------+       |
|                                    [ Loopback Network: 127.0.0.1 ]                                |
|                                                                                                   |
|   +-------------------------------------------------------------------------------------------+   |
|   | 核心挂载卷与外部映射边界 (External Volumes & Bind Mounts)                                      |   |
|   |                                                                                           |   |
|   |  - /opt/wvp/config/application.yml   <===> Host: ./config/application.yml (WVP主配置)      |   |
|   |  - /opt/media/conf/config.ini        <===> Host: ./config/zlm.ini         (流媒体配置)     |   |
|   |  - /etc/redis.conf                   <===> Host: ./config/redis.conf      (缓存配置)       |   |
|   |  - /var/lib/mysql                    <===> Host: ./data/mysql             (MariaDB数据)    |   |
|   |  - /opt/media/bin/www/record         <===> Host: ./data/record            (录像切片)       |   |
|   |  - /opt/wvp/logs                     <===> Host: ./logs                   (平台运行日志)   |   |
|   +-------------------------------------------------------------------------------------------+   |
+---------------------------------------------------------------------------------------------------+
       ^                          ^                             ^                          ^
       | 18080 (Web UI & REST)    | 8116 / 5060 (SIP 信令)       | 8000/udp (WebRTC 对讲)    | 30000-30050
       |                          |                             |                          | (RTP 收流)
+---------------------------------------------------------------------------------------------------+
| 宿主机环境 (Host Environment: Linux / macOS / Windows)                                             |
+---------------------------------------------------------------------------------------------------+
```

### 3.2 五阶段极致轻量与多架构加速构建流水线 (Multi-Stage Build Pipeline)

```mermaid
graph TD
    A[Stage 1: web-builder<br/>--platform=$BUILDPLATFORM] -->|静态资源 dist/| D[Stage 3: wvp-builder<br/>--platform=$BUILDPLATFORM]
    B[Stage 2: jre-builder<br/>--platform=$TARGETPLATFORM] -->|jlink 裁剪 JRE ~45MB| E[Stage 5: final-runner<br/>--platform=$TARGETPLATFORM]
    C[Stage 4: zlm-builder<br/>--platform=$TARGETPLATFORM] -->|编译并 strip 后 MediaServer ~12MB| E
    D -->|可执行 wvp.jar ~60MB| E
    
    subgraph Stage 1: 前端构建 (Node.js 原生硬件加速)
        A1[拉取 Node.js 20 Alpine] --> A2[安装 npm 依赖] --> A3[npm run build:prod]
    end
    
    subgraph Stage 2: JRE定制 (Eclipse Temurin JDK 21 Alpine)
        B1[拉取目标架构 JDK 21 Alpine] --> B2[jlink 模块分析与剥离] --> B3[生成目标微型 JRE]
    end
    
    subgraph Stage 3: 后端编译 (Maven 原生硬件加速)
        D1[合并 Stage 1 前端代码至 static/] --> D2[mvn clean package -DskipTests]
    end

    subgraph Stage 4: 流媒体编译 (Alpine C++ Toolchain)
        C1[编译 openssl / libsrtp] --> C2[cmake 编译 ZLM WebRTC] --> C3[strip MediaServer]
    end

    subgraph Stage 5: 最终生产镜像 (Alpine 3.20 Minimal)
        E1[安装 mariadb + redis + libsrtp + ffmpeg]
        E2[组装 JRE + wvp.jar + MediaServer + 优雅停机脚本]
        E3[压缩清理包缓存与临时文件]
    end
```

### 3.3 MariaDB 数据库初始数据注入与表名大小写合规方案

1. **引擎规范**：采用 Alpine `mariadb`。
2. **表名大小写合规基线**：在首次调用 `mysql_install_db` 初始化时，**必须显式声明 `--lower-case-table-names=1`**，确保系统表与业务表在 Linux 下均统一使用小写比对，与 WVP 的 MyBatis 映射规范严格对齐。
3. **初始化 SQL 自动灌入**：
   - 镜像内部固化建表脚本：`初始化-mysql-2.7.4.sql`。
   - 首次启动检测到 `/var/lib/mysql` 为空时，通过初始化模式启动临时 mysqld，执行库表建立与默认数据灌入：
     ```bash
     mysql_install_db --user=mysql --datadir=/var/lib/mysql --lower-case-table-names=1
     /usr/bin/mysqld --user=mysql --bootstrap --lower-case-table-names=1 < /opt/wvp/init.sql
     ```
   - 支持宿主机挂载外置扩展脚本，实现企业定制数据的无侵入增量更新。

---

## 4 Work Breakdown Structure (WBS 工作分解结构与实施步骤)

```
WBS: WVP All-in-One 镜像发布方案
├── 1. 镜像构建资产设计 (Container Assets Design)
│   ├── 1.1 编写多架构加速 Dockerfile (docker/aio/Dockerfile)
│   │   ├── Stage 1: web 前端编译 (强制 --platform=$BUILDPLATFORM 原生加速)
│   │   ├── Stage 2: jlink JRE 21 微型定制层 (--platform=$TARGETPLATFORM)
│   │   ├── Stage 3: Maven 后端打包 (强制 --platform=$BUILDPLATFORM 原生加速)
│   │   ├── Stage 4: ZLMediaKit Alpine 编译与符号剥离 (--platform=$TARGETPLATFORM)
│   │   └── Stage 5: Alpine 运行时装配层
│   ├── 1.2 编写进程编排与配置自愈脚本 (docker/aio/entrypoint.sh)
│   │   ├── 信号拦截与级联逆序优雅停机（SIGTERM / SIGINT 捕获）
│   │   ├── MariaDB 首次 lower-case-table-names=1 初始化与建表注入
│   │   ├── 外部外挂配置文件缺省自动生成机制（自愈特性）
│   │   └── 四组件（MariaDB、Redis、ZLM、WVP）健康探测与有序拉起
│   └── 1.3 核心配置模板归约 (docker/aio/conf/)
│       ├── application-aio.yml (适配 127.0.0.1 闭环通信)
│       ├── zlm-config.ini (启用 WebRTC 与合理 RTP 端口池)
│       └── redis-aio.conf (轻量化内存配置)
├── 2. iMac (macOS Colima) 交叉编译与发布流水线 (Build & Publish Pipeline)
│   ├── 2.1 Colima 容器引擎多架构环境准备 (`docker buildx create`)
│   ├── 2.2 编写全自动多架构编译发布脚本 (`docker/aio/build.sh`)
│   └── 2.3 Docker Hub 凭据注入与镜像语义化版本打标 (`reaticle/wvp-pro-aio:2.7.4`)
├── 3. 工程师极速交付物套件 (Quick-Start Delivery Kit)
│   ├── 3.1 单机一键运行命令 (`docker run` 极简版与 host 版)
│   ├── 3.2 工程师一键编排模板 (`docker-compose.aio.yml`)
│   └── 3.3 外挂目录生成工具 (`setup-workspace.sh`)
└── 4. 质量门禁与验证矩阵 (Verification & Gatekeeping)
```

### 4.1 详细任务定义

#### 阶段 1：构建资产与脚本核心逻辑设计

- **Task 1.1: JRE 21 精简指令规范 (`jlink`)**
  针对 Spring Boot 3.4.4 及 WVP-PRO 运行时，定制 `jlink` 参数：
  ```bash
  $JAVA_HOME/bin/jlink \
      --add-modules java.base,java.compiler,java.desktop,java.instrument,java.management,java.naming,java.net.http,java.prefs,java.rmi,java.scripting,java.security.jgss,java.security.sasl,java.sql,java.sql.rowset,java.transaction.xa,java.xml,jdk.crypto.cryptoki,jdk.crypto.ec,jdk.unsupported,jdk.management \
      --strip-debug \
      --no-man-pages \
      --no-header-files \
      --compress=2 \
      --output /opt/java-runtime
  ```
  *预期收益*：从 450MB+ 的完整 JDK 缩小至 ~48MB 独立 JRE。

- **Task 1.2: 进程监督引擎与优雅停机逻辑 (`docker/aio/entrypoint.sh`)**
  消除直接 `exec java` 带来的 PID 1 强杀隐患，实现可靠守护：
  ```bash
  #!/bin/sh
  set -e

  # 1. 优雅停机信号处理函数
  stop_services() {
      echo "[Shutdown] 接收到停机信号，正在有序停止服务..."
      if [ -n "$WVP_PID" ]; then
          echo "[Shutdown] 正在停止 WVP-PRO (PID: $WVP_PID)..."
          kill -TERM "$WVP_PID" 2>/dev/null
          wait "$WVP_PID" 2>/dev/null || true
      fi
      if [ -n "$ZLM_PID" ]; then
          echo "[Shutdown] 正在停止 ZLMediaKit (PID: $ZLM_PID)..."
          kill -TERM "$ZLM_PID" 2>/dev/null
          wait "$ZLM_PID" 2>/dev/null || true
      fi
      echo "[Shutdown] 正在停止 Redis..."
      redis-cli shutdown 2>/dev/null || true
      echo "[Shutdown] 正在安全刷新 MariaDB 脏页..."
      mysqladmin shutdown 2>/dev/null || true
      echo "[Shutdown] 全组件已安全退出。"
      exit 0
  }
  trap stop_services SIGTERM SIGINT

  # 2. 配置自愈检查
  if [ ! -f /opt/wvp/config/application.yml ]; then
      echo "[Init] 检测到宿主未配置 application.yml，正在初始化默认配置..."
      cp /opt/wvp/templates/application-aio.yml /opt/wvp/config/application.yml
  fi
  if [ ! -f /opt/media/conf/config.ini ]; then
      cp /opt/wvp/templates/zlm-config.ini /opt/media/conf/config.ini
  fi

  # 3. MariaDB 初始化与数据注入
  if [ ! -d "/var/lib/mysql/mysql" ]; then
      echo "[Init] 首次启动，初始化 MariaDB 数据目录..."
      mysql_install_db --user=mysql --datadir=/var/lib/mysql --lower-case-table-names=1
      /usr/bin/mysqld --user=mysql --bootstrap --lower-case-table-names=1 < /opt/wvp/init.sql
  fi

  # 4. 有序拉起服务
  echo "[Start] 启动 Redis 缓存服务..."
  redis-server /etc/redis.conf --daemonize yes

  echo "[Start] 启动 MariaDB 数据库..."
  /usr/bin/mysqld_safe --user=mysql --datadir=/var/lib/mysql --lower-case-table-names=1 &

  echo "[Start] 启动 ZLMediaKit 流媒体引擎..."
  /opt/media/bin/MediaServer -c /opt/media/conf/config.ini -d &
  ZLM_PID=$!

  echo "[Start] 启动 WVP-PRO 核心信令服务..."
  /opt/java-runtime/bin/java -jar /opt/wvp/wvp.jar --spring.config.location=/opt/wvp/config/application.yml &
  WVP_PID=$!

  # 5. 守护等待 WVP 退出
  wait "$WVP_PID"
  ```

#### 阶段 2：iMac 编译发布自动化体系

- **Task 2.1: Dockerfile 多阶段架构解耦与 Buildx 命令设计**
  通过在 Dockerfile 中精准解耦架构无关层，避免在 iMac 上通过 QEMU 模拟编译 Java 和 Node：
  ```dockerfile
  # 架构无关阶段（利用宿主机原生 CPU 极速打包）
  FROM --platform=$BUILDPLATFORM node:20-alpine AS web-builder
  # ... 执行 npm build ...

  FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-21-alpine AS wvp-builder
  # ... 执行 mvn package ...

  # 架构匹配阶段（针对目标 CPU 编译 C++ 与安装库）
  FROM --platform=$TARGETPLATFORM eclipse-temurin:21-jdk-alpine AS jre-builder
  # ... 执行 jlink ...

  FROM --platform=$TARGETPLATFORM alpine:3.20 AS zlm-builder
  # ... 编译 ZLM 并 strip ...

  FROM --platform=$TARGETPLATFORM alpine:3.20 AS final-runner
  # ... 装配最终镜像 ...
  ```

  在 iMac 配合机终端，一键全自动打包发布：
  ```bash
  # 创建并激活 buildx 实例
  docker buildx create --name wvp-builder --use --driver docker-container

  # 编译并推送到 Docker Hub
  docker buildx build \
      --platform linux/amd64,linux/arm64 \
      -t ${DOCKER_HUB_USER}/wvp-pro-aio:2.7.4 \
      -t ${DOCKER_HUB_USER}/wvp-pro-aio:latest \
      -f ./docker/aio/Dockerfile \
      --push .
  ```

#### 阶段 3：工程师极速体验工作流 (Quick-Start Delivery Kit)

- **Task 3.1: 工程师本地一键编排启动 (`docker-compose.yml`)**
  为适配绝大多数工程师在日常开发、测试及生产部署场景，提供标准化的 `docker-compose.yml` 编排模板。该模板既支持在 macOS / Windows / 容器云上的 **Bridge 显式端口映射模式**，也支持通过注释切换至 Linux 裸机的 **Host 直通模式**：

  ```yaml
  # docker-compose.yml
  version: '3.8'

  services:
    wvp-aio:
      image: reaticle/wvp-pro-aio:2.7.4
      container_name: wvp-aio
      restart: always
      environment:
        TZ: Asia/Shanghai
        # 容器内默认采用 127.0.0.1 闭环互通，如需外部注入可在此处覆盖
        # SIP_HOST: 192.168.1.100
      volumes:
        # 1. 核心配置文件挂载（外挂热更新，重启容器生效）
        - ./config:/opt/wvp/config
        - ./config/zlm.ini:/opt/media/conf/config.ini
        - ./config/redis.conf:/etc/redis.conf
        # 2. 持久化数据存储（数据物理落盘，容器销毁不丢失）
        - ./data/mysql:/var/lib/mysql
        - ./data/record:/opt/media/bin/www/record
        # 3. 运行日志统一归集
        - ./logs/wvp:/opt/wvp/logs
        - ./logs/media:/opt/media/log
        - ./logs/mysql:/var/log/mysql
      # -------------------------------------------------------------
      # 模式 A: Linux 生产直通推荐（若启用，请将下面的 ports 段全部注释）
      # network_mode: host
      # -------------------------------------------------------------
      # 模式 B: 标准 Bridge 显式端口映射（兼容 macOS/Win/云服务器）
      ports:
        # [核心 1] WVP 管理后台与 RESTful 接口
        - "18080:18080/tcp"

        # [核心 2] GB28181 SIP 信令接入（摄像头注册与心跳）
        - "8116:8116/udp"
        - "8116:8116/tcp"

        # [核心 3] ZLMediaKit HTTP 流媒体点播 (HTTP-FLV / HLS) 与 REST API
        - "9092:9092/tcp"

        # [核心 4] WebRTC 媒体协商与语音对讲核心端口 (必须暴露 UDP)
        - "8000:8000/udp"
        - "8000:8000/tcp"

        # [核心 5] 经典多媒体推拉流协议
        - "1935:1935/tcp"    # RTMP 推拉流
        - "1935:1935/udp"
        - "554:554/tcp"      # RTSP 推拉流
        - "554:554/udp"

        # [核心 6] GB28181 RTP 国标媒体收流端口段（按需调整范围，建议与 zlm.ini 保持一致）
        - "30000-30050:30000-30050/udp"
        - "30000-30050:30000-30050/tcp"

        # [可选调试] 数据库与缓存管理端口（生产环境建议注释，避免暴露到公网）
        - "3306:3306/tcp"    # MariaDB 数据调试
        - "6379:6379/tcp"    # Redis 状态调试
  ```

- **Task 3.2: 全栈对外端口映射矩阵与防火墙放行规范 (Port Mapping Matrix)**

  针对单镜像内包含的多协议服务，其监听端口与业务作用对照如下：

  | 序号 | 暴露端口 | 协议类型 | 服务角色 | 核心功能与业务场景 | 生产建议 |
  |---|---|---|---|---|---|
  | 1 | **`18080`** | TCP | WVP-PRO | Web 管理控制台、RESTful API、Swagger 在线文档、前端静态界面托管 | **必选放行** |
  | 2 | **`8116`** | UDP & TCP | WVP-PRO (SIP) | GB28181 国标信令端口，用于摄像机注册、鉴权、状态心跳（Keepalive）及 PTZ 控制 | **必选放行** (UDP 为主) |
  | 3 | **`9092`** | TCP | ZLMediaKit | 流媒体 HTTP REST API、HTTP-FLV / HLS / WebSocket-FLV 实时播放源 | **必选放行** |
  | 4 | **`8000`** | UDP & TCP | ZLMediaKit | WebRTC 媒体流收发、**双向语音对讲**、浏览器端超低延迟点播（毫秒级） | **对讲必选** (UDP 为核心) |
  | 5 | **`1935`** | TCP & UDP | ZLMediaKit | RTMP 实时视频流发布与拉取，兼容 OBS / FFmpeg 常见推流工具 | 可选放行 |
  | 6 | **`554`** | TCP & UDP | ZLMediaKit | RTSP 实时流分发与拉流代理（支持 VLC / 监控大屏拉流） | 可选放行 |
  | 7 | **`30000~30050`** | UDP & TCP | ZLMediaKit (RTP) | GB28181 国标推流端口池，用于接收摄像头 PS-RTP 音视频数据流（多端口并发） | **推流必选** (范围可伸缩) |
  | 8 | **`3306`** | TCP | MariaDB | 数据库管理端口，支持 Navicat / DBeaver 本地直连调试 | **仅内网/调试暴露** |
  | 9 | **`6379`** | TCP | Redis | 缓存管理端口，支持 RedisInsight 查看 Session / 心跳键值 | **仅内网/调试暴露** |

  > [!TIP]
  > **Bridge 模式 vs Host 模式深度抉择：**  
  > - **macOS / Windows 配合机**：Docker 底层依赖轻量虚拟机，无法直接使用 `--net=host`，必须采用上述 `ports` 显式端口映射机制。
  > - **Linux 裸机生产环境**：由于 GB28181 摄像头并发路数增加时需要分配更多 RTP 收流端口（如 `30000~30500` 共 500 个端口），过多的 Docker 端口映射会导致宿主机 `iptables` 规则极度臃肿并引发 NAT 性能骤降。因此在 Linux 宿主上，强烈推荐取消注释 `network_mode: host`，实现零虚拟化开销的原生网络吞吐。


---

## 5 核心配置文件映射设计 (External Mounting Architecture)

为保障生产环境的高可用与便捷定制，容器设计标准外挂骨架如下：

```text
宿主机外挂根目录 (如 /data/wvp-aio/)
├── config/                      # 核心配置文件目录（支持热修改重启生效）
│   ├── application.yml          # [核心 1] WVP 业务与国标 SIP 配置
│   ├── zlm.ini                  # [核心 2] ZLMediaKit 流媒体与对讲端口配置
│   └── redis.conf               # [核心 3] Redis 参数定制
├── data/                        # 数据持久化目录
│   ├── mysql/                   # MariaDB 物理数据存储（容器销毁数据永驻）
│   └── record/                  # ZLM 录像切片与快照存储
└── logs/                        # 统一日志归集目录
    ├── wvp/                     # Spring Boot 业务日志
    ├── media/                   # ZLMediaKit 流媒体收发日志
    └── mysql/                   # MariaDB 慢查询与错误日志
```

### 5.1 容器出厂默认参数联动规则
在单镜像内部，四个组件的通讯链路均以 `127.0.0.1` 环回接口锚定，天然具备免疫外部 IP 变动的稳定性：
- **MariaDB 访问**：`jdbc:mysql://127.0.0.1:3306/wvp?useUnicode=true&characterEncoding=UTF8`
- **Redis 访问**：`127.0.0.1:6379`，初始无密码或默认简单密码。
- **ZLM 控制调用**：`media.ip = 127.0.0.1`，`media.http-port = 9092`。
- **ZLM Webhook 回调**：`media.hook-ip = 127.0.0.1`（直接回调同一容器内部的 18080，再无被宿主防火墙拦截的风险）。
- **对外暴露配置**：工程师仅需在宿主外挂的 `application.yml` 中配置宿主机的外部局域网 IP（`sip.ip`），摄像机即可完成推流与信令交互。

---

## 6 Acceptance Criteria (验收指标与交付门禁)

方案落地后的验收必须满足以下量化门禁标准：

| 验收维度 | 指标项 | 门禁阈值 / 合格标准 | 验证手段 |
|---|---|---|---|
| **包体体积** | Docker 镜像 Compressed Size | **$\le$ 280 MB** (Docker Hub 传输体积) | `docker images` 查看 Virtual Size；Docker Hub 页面查看压缩传输包体积。 |
| **包体体积** | 本地解压镜像磁盘占用 | **$\le$ 650 MB** | `docker inspect -f "{{ .Size }}" <IMAGE>` |
| **构建跨平台** | 架构支持覆盖 | 完整支持 `linux/amd64` 与 `linux/arm64` | 在 Intel 服务器与 Apple Silicon / 树莓派各拉取运行并成功进入 Web 界面。 |
| **启动时延** | 容器从拉起到四组件全绿 | **$\le$ 25 秒**（机械硬盘 $\le 45$ 秒） | 检查各服务端口监听就绪时间戳差值。 |
| **内存底噪** | 零路视频流空载运行时内存 | **$\le$ 450 MB**（JVM 堆限 256M~512M） | `docker stats --no-stream` 检查容器内存基线。 |
| **配置自愈** | 空目录挂载容错性 | 映射宿主空目录时，**容器不崩溃且自动生成默认模板** | 挂载全空 `./config` 目录启动，检验是否自动拷贝初始文件并正常启动。 |
| **功能闭环** | GB28181 信令与流媒体 | 设备上线注册成功、点播 HLS/WebRTC 成功、录像计划可落盘 | 接入国标模拟器或真实摄像头执行推流回放验证。 |
| **信号优雅停机** | 优雅退出时效与脏页刷盘 | `docker stop` 在 **10 秒内**完成逆序退出，**零 SIGKILL 强杀记录** | 检查 MariaDB 与 ZLM 日志无 crash recovery 报错，InnoDB buffer clean。 |

---

## 7 下一步实施指南 (Execution Transition)

依据本架构方案，后续工程执行建议分步推进：
1. 在项目根目录创建 `docker/aio/` 专用目录，沉淀 `Dockerfile`、`entrypoint.sh` 及预置配置文件模板；
2. 在 iMac 配合机执行多架构交叉构建测试，验证 `--platform=$BUILDPLATFORM` 加速效果与镜像体积；
3. 执行多架构 Hub 推送，编制一键运行 Markdown 指南归档至 `doc/reaticle_docs/`。
