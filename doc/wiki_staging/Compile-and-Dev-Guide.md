# 编译与开发指南

WVP-PRO 可实现 GB28181-2016 / 2022 的 SIP 信令协议与 ONVIF Profile S/T 原生协议接入，本身也是一个集流媒体控制、设备树管理、通道分屏播放、录像回放与语音对讲于一体的高性能安防视频管理平台。

为了让开发者以最快速度搭建起干净、高可用、可断点调试的研发环境，本文档基于真实敏捷工程实践，提供 **“主开发机（Windows 11） + 离线测试机（Linux 192.168.30.x） + 镜像打包机（iMac 26.1 Colima）”** 的三机分工协同开发体系：
- **配合机（iMac 26.1, `192.168.120.11`）**：具备国际互联网访问条件，**专职作为 Docker 镜像拉取、编译、构建与离线镜像打包中枢（Build & Packaging Host）**；
- **离线测试机（Linux, `192.168.30.x`）**：处于内网无互联网条件，**负责离线加载镜像并承载全套基础设施容器（Offline Infra & Media Host）**；
- **主开发机（Windows 11, `192.168.30.252`）**：与测试机同处于 `192.168.30.x` 平坦局域网，**专职运行 IDE（Antigravity / VS Code）断点调试 Java 后端与前端热重载 DevServer（Dev Host）**。

遇到问题可以：
1. 查阅项目 Wiki 与常见问答；
2. 加入星球提问：[知识星球](https://t.zsxq.com/0d8VAD3Dm)；
3. 向原作者发送邮件 `648540858@qq.com` 寻求技术支持（有偿）；
4. 向本分支维护人发送邮件 `y.wang@reaticle.com`。

---

## 1 服务架构与三机协同分工拓扑

在完整的 WVP-PRO 视频监控体系中（支持 GB28181 国标与 ONVIF 双协议），核心角色分工与物理宿主矩阵如下：

| 服务角色 | 功能说明 | 宿主设备与定位 | 网络环境 | 是否必须 |
|---|---|---|---|---|
| **WVP-PRO** | SIP / ONVIF 信令交互、通道纳管、PTZ 控制及业务控制 RESTful/Hook API | **主开发机（Windows 11, `192.168.30.252`）** | 内网物理以太网直连 | **是** |
| **Vue Web 前端** | 设备监控树、分屏点播播放、录像回放、系统配置及对讲交互界面 | **主开发机（Windows 11）** DevServer (:9528) | 本地回环与内网暴露 | **是** |
| **ZLMediaKit (ZLM)** | RTP 收发、音视频转码分发（WebRTC/RTSP/RTMP/FLV/HLS）及语音对讲 | **离线测试机（Linux, `192.168.30.x`）** | 局域网无外网环境 | **是** |
| **MySQL 8.0** | 持久化存储设备、通道、录像计划与平台配置数据 | **离线测试机（Linux, `192.168.30.x`）** | 局域网无外网环境 | **是**（支持 H2 临时替代） |
| **Redis 7.0** | SIP 事务缓存、心跳状态维护、Session 路由与信令锁 | **离线测试机（Linux, `192.168.30.x`）** | 局域网无外网环境 | **是** |
| **Docker 镜像中枢** | 联网拉取、编译定制镜像、导出 `wvp-infra-images.tar.gz` 离线分发包 | **配合机（iMac 26.1 Colima, `192.168.120.11`）** | 具备优质互联网条件 | **是（运维构建）** |

### 1.1 为什么采用“iMac 专职打包 + Linux 离线运行 + Windows 调试”三机架构？

- **彻底规避网络代理（Clash TUN）干扰**：Windows 主开发机与 Linux 测试机同处 `192.168.30.0/24` 物理局域网，数据走二层 ARP 直达，**100% 绕开 Clash 虚拟网卡的流嗅探与 Server-First 握手截断**，彻底根除 MySQL 连库报 `EOFException` / `Got an error reading communication packets` 的暗坑；
- **打通 ZLM 双向 Webhook 与语音对讲**：ZLM 在同一个平坦内网可毫无阻碍地将事件回调（`hook-ip: 192.168.30.252:18080`）直接推送至 Windows 开发机，真实摄像头的 RTP 码流与 WebRTC UDP 8000 对讲再无跨网段穿透阻隔；
- **权责分明、离线安全**：iMac 凭借互联网条件充当“制品工厂”，负责处理一切 Docker Hub 依赖拉取与打包；`192.168.30.x` 产研局域网即使完全断网，也能稳定进行全功能闭环开发。

```
+--------------------------------------------------+
|      配合构建机 (iMac 26.1, 192.168.120.11)       |
|            [具备优质互联网连接条件]                  |
|  - 联网拉取: mysql:8.0, redis:7.0, zlmediakit     |
|  - 离线导出: docker save | gzip > infra.tar.gz    |
+-------------------------+------------------------+
                          |
                          | (离线镜像压缩包分发)
                          v
+---------------------------------------------------------------------------------------------------+
|                        192.168.30.0/24 纯内网平坦局域网 (无互联网条件)                               |
|                                                                                                   |
|   +---------------------------------------+       +-------------------------------------------+   |
|   |   主开发机 (Windows 11: 192.168.30.252)|       |    离线测试机 (Linux: 192.168.30.x)       |   |
|   |                                       |       |                                           |   |
|   |   [Antigravity / VS Code]             |       |   [Docker Engine 离线载入环境]             |   |
|   |   - WVP-PRO (Java 21): 18080          |       |   - MySQL 8.0 容器 : 3306 (持久化)         |   |
|   |   - SIP 28181 服务   : 8116           |       |   - Redis 7.0 容器 : 6379 (缓存/锁)        |   |
|   |   - ONVIF Discovery : 3702            |       |   - ZLMediaKit    : 9092 HTTP REST        |   |
|   |                                       |       |     WebRTC 语音对讲: 8000 UDP             |   |
|   |   [Vue CLI DevServer: 9528]           |       |     RTP 收流端口段 : 40000~45000          |   |
|   |                                       |       |                                           |   |
|   |      JDBC (3306) / Redis (6379) 直连   |       |                                           |   |
|   |   ===================================>|       |                                           |   |
|   |                                       |       |                                           |   |
|   |      ZLM Webhook 回调 (至 :18080)     |       |                                           |   |
|   |   <-----------------------------------|       |                                           |   |
|   +---------------------------------------+       +-------------------------------------------+   |
+---------------------------------------------------------------------------------------------------+
```

### 1.2 整体实施先后逻辑

1. **iMac 构建并导出镜像包**：在 iMac 配合机上拉取基础镜像并执行 `docker save` 导出为 `wvp-infra-images.tar.gz`；
2. **测试机离线导入并拉起**：将镜像包与 `docker/infra` 配置拷贝至 `192.168.30.x` 测试机，离线导入镜像并运行容器；
3. **Windows 主机补齐开发环境**：确认 Java 21、Maven 与 Node.js 环境就绪；
4. **Windows 主机编译前端 Web**：编译静态资源至 `src/main/resources/static`，确保界面资源归位；
5. **Windows 主机直连调试后端**：在 `application-dev.yml` 中配置测试机 IP，在 VS Code 中按 `F5` 启动断点调试。

---

## 2 环境与依赖基线清单

| 组件/依赖 | 推荐版本 | 用途 | 安装宿主 | 验证命令 |
|---|---|---|---|---|
| **JDK** | 21 (LTS) | 运行与编译 Java 后端（启用虚拟线程） | 主开发机（Win 11） | `java -version` |
| **Maven** | >= 3.8.x | 管理 Java 依赖及打包可执行 Jar/War | 主开发机（Win 11） | `mvn -version` |
| **Git** | 最新稳定版 | 版本管理与源码拉取（需放开长路径） | 主开发机（Win 11） | `git --version` |
| **Node.js** | 18.x ~ 24.x | 编译与运行前端 Web 界面 | 主开发机（Win 11） | `node -v` |
| **npm** | 对应 Node 自带 | 管理前端 npm 依赖包 | 主开发机（Win 11） | `npm -v` |
| **Docker Engine & Compose** | >= 24.x / Compose v2 | 离线加载镜像并拉起 MySQL、Redis、ZLM | 离线测试机（Linux 30.x） | `docker info` / `docker compose version` |
| **Colima & Docker** | 最新稳定版 | 镜像拉取、编译构建与打包导出 | 配合机（iMac 26.1） | `colima status` / `docker info` |

> [!IMPORTANT]
> 当前 Git 源码仓库默认**未包含**预编译的前端静态资源（`src/main/resources/static`）。首次全量打包或独立启动后端前，**必须执行前端编译**，否则后端启动后访问 Web 管理界面将报 404 错误。

---

## 3 配合机（iMac）镜像构建打包与离线测试机（Linux 192.168.30.x）部署流水线

在实际产研交付中，`192.168.30.x` 测试网段通常处于内网隔离状态（**无互联网访问条件**），无法直接从 Docker Hub 下载镜像；而直接跨网段连接 iMac（`192.168.120.11`）又容易受 Windows 宿主代理（Clash TUN）干扰。因此，最佳敏捷实践为：**由具备国际互联网访问条件的 iMac 专职承担 Docker 镜像拉取、编译、构建与离线打包，随后分发至 30.x 测试机离线运行**。

### 3.1 配合机（iMac 26.1）镜像拉取与离线打包

配合机（iMac）作为镜像工厂，负责拉取并导出全套经过验证的基础镜像。

在 iMac 终端中执行以下操作：

```bash
# 1. 确保 Colima 守护进程正常运行
colima status

# 2. 进入或创建本地基础设施目录
mkdir -p ~/projects/wvp-infra && cd ~/projects/wvp-infra

# 3. 联网拉取全量官方认证基础镜像（显式指定测试机所需的 linux/amd64 架构，避免 Mac 默认拉取 arm64）
docker pull --platform linux/amd64 mysql:8.0
docker pull --platform linux/amd64 redis:7.0
docker pull --platform linux/amd64 zlmediakit/zlmediakit:master

# 4. 一键将三款镜像合并打包并进行 gzip 高压缩导出（约 500~600MB）
docker save --platform linux/amd64 mysql:8.0 redis:7.0 zlmediakit/zlmediakit:master | gzip > wvp-infra-images.tar.gz

# 5. 校验镜像包完整性与大小
ls -lh wvp-infra-images.tar.gz
```

---

### 3.2 离线镜像包与工程编排配置跨网中转分发

Windows 11 开发机同时具备跨网段访问 iMac（`192.168.120.11`）与以太网直连测试机（`192.168.30.x`）的能力，充当中转桥梁：

假设 30.x Linux 测试机 IP 为 `192.168.30.100`，SSH 账户为 `root`：

在 **Windows 11 PowerShell** 中执行极速传输：

```powershell
# 1. 从 iMac 下载打包好的离线镜像压缩包至 Windows 本地
scp reaticle@192.168.120.11:~/projects/wvp-infra/wvp-infra-images.tar.gz D:\wvp-infra-images.tar.gz

# 2. 将离线镜像包推送到 192.168.30.100 Linux 测试机
scp D:\wvp-infra-images.tar.gz root@192.168.30.100:/tmp/

# 3. 将 Windows 本地工程内完整的 docker/infra 编排目录推送到 Linux 测试机
scp -r d:\offices\Github\wvp-GB28181-pro\docker\infra root@192.168.30.100:~/wvp-infra
```

---

### 3.3 离线测试机（Linux 192.168.30.x）镜像加载与一键拉起

登录至 **192.168.30.x Linux 测试机**（无需互联网连接）：

```bash
# 1. 离线载入 Docker 镜像
docker load -i /tmp/wvp-infra-images.tar.gz

# 2. 确认镜像已成功注入本地 Docker 存储
docker images | grep -E 'mysql|redis|zlmediakit'

# 3. 进入编排目录并一键在后台启动全部服务
cd ~/wvp-infra
docker compose up -d

# 4. 检查服务健康状态与端口监听
docker compose ps
```

---

### 3.4 编排配置规范与离线加固说明

`docker/infra/docker-compose.yml` 统一定义并加固了三大服务：

```yaml
version: '3.8'

services:
  # MySQL 8.0 持久化服务
  wvp-mysql:
    image: mysql:8.0
    container_name: wvp-mysql
    restart: unless-stopped
    ports:
      - "3306:3306"
    environment:
      MYSQL_ROOT_PASSWORD: root
      MYSQL_DATABASE: wvp
      MYSQL_USER: wvp_user
      MYSQL_PASSWORD: wvp_password
      TZ: Asia/Shanghai
    volumes:
      - ./mysql/conf.d:/etc/mysql/conf.d:ro
      - ./mysql/initdb:/docker-entrypoint-initdb.d:ro
      - mysql-data:/var/lib/mysql
    networks:
      - wvp-net

  # Redis 缓存服务
  wvp-redis:
    image: redis:7.0
    container_name: wvp-redis
    restart: unless-stopped
    ports:
      - "6379:6379"
    environment:
      TZ: Asia/Shanghai
    volumes:
      - ./redis/redis.conf:/usr/local/etc/redis/redis.conf:ro
      - redis-data:/data
    command: ["redis-server", "/usr/local/etc/redis/redis.conf"]
    networks:
      - wvp-net

  # ZLMediaKit 高性能流媒体服务（支持 WebRTC 语音对讲与 RTP 收流）
  wvp-zlm:
    image: zlmediakit/zlmediakit:master
    container_name: wvp-zlm
    restart: always
    ports:
      - "9092:80"                      # HTTP REST API & 流播放
      - "1935:1935"                    # RTMP
      - "554:554"                      # RTSP
      - "8000:8000/udp"                # WebRTC 语音对讲核心端口 (UDP)
      - "10000:10000/udp"
      - "10000:10000/tcp"
      - "40000-40050:40000-40050/udp"  # GB28181 RTP 收流端口段
      - "40000-40050:40000-40050/tcp"
    environment:
      TZ: Asia/Shanghai
    volumes:
      - ./zlm/config.ini:/opt/media/conf/config.ini:ro
      - zlm-record:/opt/media/bin/www/record
    command: ["MediaServer", "-c", "/opt/media/conf/config.ini", "-l", "0"]
    networks:
      - wvp-net

networks:
  wvp-net:
    driver: bridge

volumes:
  mysql-data:
  redis-data:
  zlm-record:
```

#### 关键避坑加固点：
1. **`skip-name-resolve` 禁用主机名反向解析**：
   在 `mysql/conf.d/my.cnf` 中已显式声明 `skip-name-resolve`。若无此配置，MySQL 会在内网连接进入时尝试进行 PTR 域名反向查找，导致连接挂起数秒并抛出 `Got an error reading communication packets`。
2. **认证插件兼容性**：
   初始化时创建的 `root` 与 `wvp_user` 账号已全面适配 `mysql_native_password`，免除在局域网内进行复杂的非对称公钥交换。
3. **初始表结构与 ONVIF 增量自动建表**：
   容器首次拉起时，挂载的 `mysql/initdb/` 目录会自动执行 `01-init.sql`（全量基础表）与 `02-onvif.sql`（ONVIF 协议增量表）。

---

### 3.5 局域网平坦网络二层直通与 Webhook 回调保障

将中间件部署于 `192.168.30.x` 测试机后，Windows 开发机与测试机处于同物理网段：

1. **WVP -> 测试机（JDBC / Redis / ZLM 控制）**：
   - 走 `192.168.30.0/24` 物理以太网卡直达，不经过任何代理软件拦截；
2. **ZLM -> WVP（Webhook 事件回调）**：
   - 当摄像头推流或播放时，ZLM 依据 `media.hook-ip: 192.168.30.252` 直接向 Windows 发起 HTTP 回调，同网段直连，无 NAT 映射失步隐患；
3. **WebRTC 语音对讲与 RTP 码流**：
   - 浏览器采集音频通过 UDP 8000 发往测试机 ZLM，对讲音频流稳定无丢包。

---

## 4 主开发机（Windows 11）基础环境就绪

主开发机已具备 Git、Node.js（如 Node 18/20/24）及 Python 环境，只需确认并补齐 Java 21 与 Maven。

### 4.1 安装与验证 JDK 21 及 Maven

若尚未安装 Java 21 与 Maven，推荐在 Windows 11 终端（PowerShell）中按如下方式配置：

```powershell
# 1. 采用 winget 安装 Eclipse Adoptium Temurin 21 JDK
winget install EclipseAdoptium.Temurin.21.JDK --accept-source-agreements --accept-package-agreements

# 2. 安装 Apache Maven（任选一种方案）
# 方案 A（推荐）：若已安装 Chocolatey 包管理器，直接一键安装：
choco install maven -y

# 方案 B：官方绿色解压安装（无包管理器环境）
# 1) 下载官方二进制包：https://dlcdn.apache.org/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.zip
# 2) 解压至常用目录（如 D:\develop\apache-maven-3.9.9）
# 3) 将其 bin 目录追加至系统环境变量 Path 中

# 3. 重启终端以刷新系统环境变量，并验证版本
java -version
# 须输出 openjdk version "21.x.x"
mvn -version
# 须输出 Apache Maven 3.8+ 及 Java version 21
```

*(若手动下载解压安装 JDK 或 Maven，请确保将 JDK 根路径设为系统变量 `JAVA_HOME`，并将 `%JAVA_HOME%\bin` 与 Maven 的 `bin` 路径追加至系统 `Path`。)*

### 4.2 Node.js 构建参数兼容（针对 Node >= 17）

本项目前端基于 Vue CLI 4（Webpack 4）。在 Node.js 17 及以上版本（包括当前机器上的 Node 24）中，Node 核心底层默认切换至 OpenSSL 3.0，而 Webpack 4 内部依赖了已被弃用的 MD4 加密算法，直接编译会抛出 `error:0308010C:digital envelope routines::unsupported` 错误。

在运行任何前端命令前，必须在 Windows 终端注入兼容环境变量：
- **PowerShell**（当前会话生效）：
  ```powershell
  $env:NODE_OPTIONS="--openssl-legacy-provider"
  ```
- **CMD**：
  ```cmd
  set NODE_OPTIONS=--openssl-legacy-provider
  ```

### 4.3 Git 文件长路径限制放开

Windows 默认存在 260 字符的文件路径限制，前端 `node_modules` 存在较深层级嵌套时容易报错。建议在终端中执行全局放开：

```powershell
git config --system core.longpaths true
```

---

## 5 主开发机（Windows 11）前端编译与资源归位

因为 WVP-PRO 源码仓库未提交预编译的静态资源，且后端以单体 Spring Boot 方式运行时会从 `src/main/resources/static` 加载 Web 界面，因此必须先完成前端编译。

### 5.1 安装前端依赖

在 Windows 11 终端中进入 `web` 目录：

```powershell
cd web

# 直连官方 npm 源极速安装
npm install
```

### 5.2 生产环境编译

执行前端构建命令：

```powershell
# 1. 注入 OpenSSL 兼容选项
$env:NODE_OPTIONS="--openssl-legacy-provider"

# 2. 生产环境编译
npm run build:prod
```

编译完成后，构建产物将自动由 `web/vue.config.js` 输出至后端静态资源目录：`src/main/resources/static`。

---

## 6 主开发机（Windows 11）后端配置与本地断点调试

有了 iMac 运行的中间件与刚刚生成的前端静态资源，现在即可在 **Antigravity / Visual Studio Code** 中一键启动后端服务。

### 6.1 获取并核对局域网网络 IP

1. **离线测试机（Linux）IP**：在 Linux 测试机终端中执行 `hostname -I` 或 `ip addr` 查看（例如 `192.168.30.100`）；
2. **主开发机（Win 11）IP**：在 PowerShell 中执行 `Get-NetIPAddress -AddressFamily IPv4` 查看物理以太网网卡当前局域网 IP（例如 `192.168.30.252`）。

### 6.2 调整本地开发配置

打开配置文件 [src/main/resources/application-dev.yml](https://github.com/TREYWANGCQU/wvp-GB28181-pro/blob/master/src/main/resources/application-dev.yml)，根据局域网平坦网络直连更新关键项（完整配置杜绝 Placeholder 缺失）：

```yaml
# src/main/resources/application-dev.yml
server:
  port: 18080

spring:
  data:
    redis:
      # 指向 192.168.30.x Linux 测试机的 Redis 容器 (或使用 SSH 映射填 127.0.0.1)
      host: 192.168.30.100
      port: 6379
      password: "" # 若容器未设密码可留空
  datasource:
    # 指向 192.168.30.x Linux 测试机的 MySQL 8.0 容器 (或使用 SSH 映射填 127.0.0.1:3306)
    url: jdbc:mysql://192.168.30.100:3306/wvp?useUnicode=true&characterEncoding=UTF8&rewriteBatchedStatements=true&serverTimezone=PRC&useSSL=false&allowMultiQueries=true
    username: root
    password: root

# 作为 28181 SIP 服务器的配置
sip:
  # 监听端口
  port: 8116
  # 主开发机当前局域网物理网卡真实 IP
  ip: 192.168.30.252
  domain: 4101050000
  id: 41010500002000000001
  password: admin

media:
  id: zlmediakit-local
  # 指向 192.168.30.x Linux 测试机上运行的 ZLM 局域网 IP
  ip: 192.168.30.100
  http-port: 9092
  # [重点避坑] 明确通知 ZLM 回调 Windows 11 主开发机的 IP 地址
  hook-ip: 192.168.30.252
  # 与 ZLM 容器中的 secret 严格匹配
  secret: 035c73f7-bb6b-4889-a715-d9eb2d1925cc
  rtp:
    enable: true
    # 与 docker/infra/docker-compose.yml 暴露端口段严格对齐
    port-range: 40000,40050
    send-port-range: 50000,55000

user-settings:
  auto-apply-play: true
  record-push-live: true
```

> [!TIP]
> **过渡备选（无需搭建测试机，极速联调）**：  
> 若尚未就绪 `192.168.30.x` 测试机，想直接使用 iMac（`192.168.120.11`）现有中间件，可利用 Windows 到 iMac 的免密 SSH，执行一条后台端口映射：  
> `Start-Process ssh -ArgumentList "-N -L 3306:127.0.0.1:3306 -L 6379:127.0.0.1:6379 reaticle@192.168.120.11" -WindowStyle Hidden`  
> 并将上述 `host` 与 `url` 改为 `127.0.0.1` 即可完美绕过 Clash 代理劫持秒级连通。

### 6.3 Antigravity / VS Code 调试环境配置

#### 1. 扩展插件就绪
在扩展商店中安装：
- **Extension Pack for Java**（包含 Java 语言服务、Maven 与 Debugger）；
- **Spring Boot Extension Pack**（可选，提供 Spring 导航与提示）。

#### 2. 工作区运行时契约（.vscode/settings.json）
在项目根目录下的 `.vscode/settings.json` 中配置 JDK 21：

```json
{
  "java.configuration.runtimes": [
    {
      "name": "JavaSE-21",
      "path": "C:\\Program Files\\Eclipse Adoptium\\jdk-21.0.x-hotspot",
      "default": true
    }
  ]
}
```

#### 3. 启动与断点调试配置（.vscode/launch.json）
在 `.vscode/launch.json` 中定义后端调试启动项：

```json
// .vscode/launch.json
{
    "version": "0.2.0",
    "configurations": [
        {
            "type": "java",
            "name": "Debug WVP-PRO (Dev)",
            "request": "launch",
            "mainClass": "com.genersoft.iot.vmp.VManageBootstrap",
            "projectName": "wvp-pro",
            "args": "--spring.profiles.active=dev"
        }
    ]
}
```

在 Antigravity / VS Code 中按下 `F5`，即可启动后端并进入断点调试模式。启动完成后，在浏览器中打开 `http://localhost:18080` 即可访问 WVP 管理界面（默认账号：`admin`，密码：`admin`）。

### 6.4 前端热重载开发模式（Dev Server）

日常进行前端界面定制开发时，无需每次重新执行 `npm run build:prod`：

1. 在终端进入 `web` 目录；
2. 执行开发服务器命令：
   ```powershell
   $env:NODE_OPTIONS="--openssl-legacy-provider"
   npm run dev
   ```
3. 前端将启动在 `http://localhost:9528`，所有发往 `/dev-api` 的请求底层由 Webpack DevServer 自动代理转发至本地后端 `http://127.0.0.1:18080`，界面代码修改可即时热重载生效。

---

## 7 全量打包发布（可选）

当需要将前后端打包为可独立部署的 Jar 或 War 时，回到项目根目录执行 Maven 构建：

### 7.1 打包可执行 Jar

```powershell
# 跳过单元测试进行打包
mvn clean package -DskipTests
```

构建成功后，在 `target/` 目录下将生成包含完整静态资源的可执行文件 `wvp-pro-2.7.4.jar`。可通过以下命令运行：

```powershell
java -jar target/wvp-pro-2.7.4.jar --spring.profiles.active=dev
```

### 7.2 打包 War 包

若需部署至外部 Servlet 容器（如 Tomcat 10+ / Jakarta EE 兼容容器）：

```powershell
mvn clean package -P war -DskipTests
```

产物 `wvp-pro-2.7.4.war` 同样位于 `target/` 目录下。

---

## 8 三机协同网络与防火墙通信矩阵

三机协同、国标设备推拉流及 ONVIF 设备接入涉及的端口及数据流向关系如下，请对照检查两端网络通畅：

| 来源端 | 目标端 | 端口号 | 传输协议 | 作用说明 | 必选 |
|---|---|---|---|---|---|
| **国标摄像头 / 设备** | **主开发机 (Win 11)** | `8116` / `5060` | UDP & TCP | GB28181 SIP 信令注册、心跳与控制交互 | **是**（国标） |
| **ONVIF 摄像头 / 设备** | **主开发机 (Win 11)** | `3702` | UDP | WS-Discovery 局域网设备自发现（多播/单播应答） | **是**（ONVIF） |
| **主开发机 (Win 11)** | **ONVIF 摄像头 / 设备** | `80` / `8080` / `8899` | TCP | ONVIF SOAP 信令交互（设备信息、PTZ、Profile 等） | **是**（ONVIF） |
| **主开发机 (Win 11)** | **离线测试机 (Linux)** | `3306` | TCP | WVP 后端访问 MySQL 8.0 数据库 | **是** |
| **主开发机 (Win 11)** | **离线测试机 (Linux)** | `6379` | TCP | WVP 后端读写 Redis 状态与锁 | **是** |
| **主开发机 (Win 11)** | **离线测试机 (Linux)** | `9092` (HTTP) | TCP | WVP 向 ZLM 发送 RESTful 控制指令 | **是** |
| **离线测试机 (Linux: ZLM)**| **主开发机 (Win 11)** | `18080` (HTTP) | TCP | ZLM 向 WVP 发送流上下线 Webhook 回调（**易被 Win 防火墙拦截**） | **是** |
| **国标摄像头 / 设备** | **离线测试机 (Linux: ZLM)**| `40000~40050` | UDP & TCP | GB28181 摄像头推送 PS-RTP 音视频媒体流 | **是**（国标） |
| **离线测试机 (Linux: ZLM)**| **ONVIF 摄像头 / 设备** | `554` | TCP | ZLMediaKit 主动向摄像头拉取 RTSP 音视频流（出站拉流代理） | **是**（ONVIF） |
| **客户端浏览器** | **离线测试机 (Linux: ZLM)**| `8000` | UDP | WebRTC 语音对讲与超低延迟拉流 | **是**（对讲必选）|
| **客户端浏览器** | **主开发机 (Win 11)** | `18080` / `9528` | TCP | 访问 WVP 管理后台 Web 界面 | **是** |
| **主开发机 (Win 11)** | **配合机 (iMac 26.1)** | `22` (SSH) | TCP | 传输离线镜像打包文件与协同构建 | **是**（运维构建） |

### 8.1 防火墙放行实施细节

#### 1. Windows 11 主开发机防火墙与网络配置（PowerShell 管理员模式）

Windows 11 默认可能会将局域网识别为公用网络（Public），从而阻断来自离线测试机或摄像头的入站连接。建议按以下步骤进行设置：

- **确认网络连接类别**：
  ```powershell
  # 查看当前网络连接类别（Public 或 Private）
  Get-NetConnectionProfile

  # 若显示为 Public，建议将当前局域网适配器（例如以太网）切换为专用网络（Private）
  Set-NetConnectionProfile -InterfaceAlias "以太网" -NetworkCategory Private
  ```

- **一键放行入站端口规则**：
  首次启动后端时若错过了 Windows Defender 弹窗提示，可在管理员权限的 PowerShell 中批量执行以下规则创建命令：
  ```powershell
  # 1. 放行 ZLM Webhook 回调与 WVP Web 管理端口（18080 TCP）
  New-NetFirewallRule -DisplayName "WVP-PRO Backend (18080 TCP)" -Direction Inbound -LocalPort 18080 -Protocol TCP -Action Allow

  # 2. 放行 GB28181 SIP 信令端口（8116 TCP & UDP）
  New-NetFirewallRule -DisplayName "WVP-PRO SIP (8116 TCP)" -Direction Inbound -LocalPort 8116 -Protocol TCP -Action Allow
  New-NetFirewallRule -DisplayName "WVP-PRO SIP (8116 UDP)" -Direction Inbound -LocalPort 8116 -Protocol UDP -Action Allow

  # 3. (可选) 放行前端开发热重载服务端口（9528 TCP）
  New-NetFirewallRule -DisplayName "WVP-PRO Web Dev (9528 TCP)" -Direction Inbound -LocalPort 9528 -Protocol TCP -Action Allow

  # 4. (若使用 ONVIF) 放行 WS-Discovery 局域网设备多播与探测应答端口（3702 UDP）
  New-NetFirewallRule -DisplayName "WVP-PRO ONVIF Discovery (3702 UDP)" -Direction Inbound -LocalPort 3702 -Protocol UDP -Action Allow
  ```

#### 2. 离线测试机（Linux）防火墙与端口监听（Linux 终端）

- **检查与放行测试机防火墙（以 UFW 或 firewalld 为例）**：
  ```bash
  # 若使用 UFW 防火墙，放行核心端口
  sudo ufw allow 3306/tcp comment 'MySQL'
  sudo ufw allow 6379/tcp comment 'Redis'
  sudo ufw allow 9092/tcp comment 'ZLM HTTP'
  sudo ufw allow 8000/udp comment 'WebRTC'
  sudo ufw allow 40000:40050/udp comment 'ZLM RTP UDP'
  sudo ufw allow 40000:40050/tcp comment 'ZLM RTP TCP'
  ```

- **验证容器端口监听**：
  ```bash
  # 验证 TCP 关键端口（MySQL 3306、Redis 6379、ZLM HTTP 9092）
  ss -tulpn | grep -E '3306|6379|9092'

  # 验证 UDP 媒体与对讲端口（WebRTC 8000、RTP 接收 40000+）
  ss -u -a | grep -E '8000|400'
  ```

#### 3. 双向网络连通性验证

环境就绪后，建议进行双向连通性测试以确保无静默拦截：

- **Windows 11 -> 30.x Linux 测试机**（在 Windows PowerShell 中执行，确保中间件二层直达）：
  ```powershell
  # 验证 MySQL 与 ZLM 端口直连畅通
  Test-NetConnection -ComputerName 192.168.30.100 -Port 3306
  Test-NetConnection -ComputerName 192.168.30.100 -Port 6379
  Test-NetConnection -ComputerName 192.168.30.100 -Port 9092
  ```

- **30.x Linux 测试机 -> Windows 11 方向**（在测试机终端中执行，确保 ZLM 回调无阻断）：
  ```bash
  # 在 Windows 启动 WVP 后端后，从测试机验证 18080 回调端口连通性
  nc -zv 192.168.30.252 18080
  # 或通过 curl 快速检测
  curl -I http://192.168.30.252:18080
  ```

- **Windows 11 -> iMac 构建机**（用于离线镜像包传输与远程打包）：
  ```powershell
  # 验证免密 SSH 打包通道
  ssh reaticle@192.168.120.11 "echo 'iMac Build Host Connected'"
  ```

### 8.2 Docker / 容器化部署网络约束

> [!WARNING]
> **WS-Discovery 多播广播必须配置 `network_mode: host`**：  
> 在 Docker 容器或 Kubernetes 环境中，默认的 Bridge 桥接网络（如 `docker0` 虚拟网桥）会丢弃 `239.255.255.250:3702` 多播组播报文。  
> 若将 WVP-PRO 容器化部署，**必须在 `docker-compose.yml` 中声明 `network_mode: host`**，否则 WS-Discovery 局域网探测将无法接收摄像头回包（此时只能使用单播指定 IP:Port 方式接入）。

---

## 附录：单机纯本地开发应急方案（外出备选）

若处于出差或脱离局域网配合机环境，主开发机可切换为纯单机独立轻量模式：

1. **轻量数据库（内置 H2 模式）**：
   打开 [src/main/resources/application-dev.yml](https://github.com/TREYWANGCQU/wvp-GB28181-pro/blob/master/src/main/resources/application-dev.yml)，注释 MySQL 数据源配置，取消注释内置 H2 数据库段（脚本位于 `数据库/2.7.4-h2/`，已内置 ONVIF 增量表结构支持），即可免装 MySQL 独立启动。
2. **本地流媒体服务**：
   访问 ZLMediaKit [官方 Release](https://github.com/ZLMediaKit/ZLMediaKit/issues/483) 下载 Windows 预编译压缩包，解压后双击运行 `MediaServer.exe`（官方发布包已内置 WebRTC 模块），并将 `media.ip` 与 `media.hook-ip` 均改回 `127.0.0.1`。

---
