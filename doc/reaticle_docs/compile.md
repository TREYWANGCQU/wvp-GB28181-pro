<!-- doc/reaticle_docs/compile.md -->

# 编译与开发指南

本分析WVP-PRO 可实现 GB28181-2016 / 2022 的 SIP 信令协议与 ONVIF Profile S/T 原生协议接入，本身也是一个集流媒体控制、设备树管理、通道分屏播放、录像回放与语音对讲于一体的高性能安防视频管理平台。

为了让开发者以最快速度搭建起干净、高可用、可断点调试的研发环境，本文档基于真实敏捷工程实践，提供 **“主开发机（Windows 11） + 配合开发机（iMac 26.1 Colima Docker）”** 的双机协同开发方案。

遇到问题可以：
1. 查阅项目 Wiki 与常见问答；
2. 加入星球提问：[知识星球](https://t.zsxq.com/0d8VAD3Dm)；
3. 向原作者发送邮件 `648540858@qq.com` 寻求技术支持（有偿）；
4. 向本分支维护人发送邮件 `y.wang@reaticle.com`。

---

## 1 服务架构与双机协同拓扑

在完整的 WVP-PRO 视频监控体系中（支持 GB28181 国标与 ONVIF 双协议），核心角色分工如下：

| 服务角色 | 功能说明 | 推荐宿主环境 | 是否必须 |
|---|---|---|---|
| **WVP-PRO** | SIP / ONVIF (SOAP/WS-Discovery) 信令交互、设备/通道注册纳管、PTZ 控制及业务控制 RESTful/Hook API | **主开发机（Windows 11）** 本地运行与断点调试 | **是** |
| **Vue Web 前端** | 设备监控树、分屏点播播放、录像回放、系统配置及对讲交互界面 | **主开发机（Windows 11）** 热重载开发或编译静态资源 | **是** |
| **ZLMediaKit (ZLM)** | 高性能流媒体服务器，负责 RTP 媒体流收发、音视频转码分发（WebRTC/RTSP/RTMP/FLV/HLS）及语音对讲处理 | **配合开发机（iMac 26.1 Colima Docker）** | **是** |
| **MySQL 8.0** | 持久化存储设备、通道、录像计划与平台配置数据 | **配合开发机（iMac 26.1 Colima Docker）** | **是**（支持 H2 临时替代） |
| **Redis** | SIP 事务缓存、设备心跳状态维护、流媒体 Session 路由与信令锁 | **配合开发机（iMac 26.1 Colima Docker）** | **是** |

### 1.1 为什么推荐双机协同开发？

- **轻量高效**：Windows 11 主机专注运行 IDE（Antigravity / VS Code）、Spring Boot 3.4+（Java 21 虚拟线程）及前端 Node.js DevServer，免除在 Windows 上配置维护复杂的 Linux 中间件；
- **原生性能与 WebRTC 支持**：iMac 上的 Colima Docker 承担 MySQL、Redis 以及高负载的 ZLMediaKit，天然拥有极佳的 Linux 内核网络栈与原生 WebRTC 语音对讲能力；
- **网络直连与秒级启停**：双机均处于国际网络环境，拉取 Docker Hub 镜像与 npm/maven 依赖通畅无阻；通过局域网高速通信，开发环境互不干扰。

```
+-------------------------------------------------------------+       +-------------------------------------------------------------+
|               主开发机 (Windows 11, 本机)                     |       |              配合开发机 (iMac 26.1, 局域网主机)               |
|                                                             |       |                                                             |
|   +--------------------------+   +----------------------+   |       |   +-----------------------------------------------------+   |
|   |   Antigravity / VSCode   |   |   Node.js (Vue CLI)  |   |       |   |                 Colima Docker Daemon                |   |
|   |  WVP-PRO (Spring Boot)   |   |   DevServer (:9528)  |   |       |   |                                                     |   |
|   |       Port: 18080        |   +----------+-----------+   |       |   |   [MySQL 8.0 Container]      [Redis Container]          |   |
|   |      SIP Port: 8116      |              | Proxy         |       |   |        Port: 3306                Port: 6379             |   |
|   |   ONVIF Discovery: 3702  |              |               |       |   |                                                     |   |
|   +------------+-------------+<-------------+               |       |   |   [ZLMediaKit Container]                            |   |
|                |  JDBC / Redis 访问连接                       | 网络  |   |   - HTTP API: 9092       - RTSP: 554                |   |
|                +============================================+======>|   |   - RTMP: 1935           - WebRTC: 8000/udp         |   |
|                |                                            |       |   |   - RTP 收流端口段: 40000~45000 (UDP/TCP)               |   |
|                |  Webhook 回调通信 (ZLM -> WVP)              |       |   |                                                     |   |
|                |<-------------------------------------------+-------+---|   hook.admin_params 向上报送流状态至 Windows 11:18080     |   |
+-------------------------------------------------------------+       +-------------------------------------------------------------+
```

### 1.2 整体实施先后逻辑

为了避免因依赖缺失而反复试错，请遵循以下先后顺序推进：
1. **配合机拉起中间件**：在 iMac 26.1 上启动 Colima Docker，并运行 MySQL、Redis、ZLMediaKit（保障后端依赖的服务先行就绪）；
2. **主机补齐编译环境**：在 Windows 11 上确认 JDK 21、Maven、Node.js 参数及 Git 配置；
3. **主机编译前端页面**：将 Vue 前端资源编译生成到 `src/main/resources/static`，确保后端启动时不缺失 Web UI；
4. **主机配置与调试后端**：在 Windows 11 配置 `application-dev.yml`，通过 Antigravity / VS Code 断点调试启动；
5. **日常前端热重载开发**：日常调试前端使用 DevServer 模式，秒级热重载。

---

## 2 环境与依赖基线清单

| 组件/依赖 | 推荐版本 | 用途 | 安装宿主 | 验证命令 |
|---|---|---|---|---|
| **JDK** | 21 (LTS) | 运行与编译 Java 后端（启用虚拟线程） | 主开发机（Win 11） | `java -version` |
| **Maven** | >= 3.8.x | 管理 Java 依赖及打包可执行 Jar/War | 主开发机（Win 11） | `mvn -version` |
| **Git** | 最新稳定版 | 版本管理与源码拉取（需放开长路径） | 主开发机（Win 11） | `git --version` |
| **Node.js** | 18.x ~ 24.x | 编译与运行前端 Web 界面 | 主开发机（Win 11） | `node -v` |
| **npm** | 对应 Node 自带 | 管理前端 npm 依赖包 | 主开发机（Win 11） | `npm -v` |
| **Python** | 3.x | 辅助工具链与脚本调用 | 主开发机（Win 11） | `python --version` |
| **Colima & Docker**| 最新稳定版 | 容器化启动 MySQL、Redis、ZLM | 配合开发机（iMac） | `colima status` / `docker info` |


> [!IMPORTANT]
> 当前 Git 源码仓库默认**未包含**预编译的前端静态资源（`src/main/resources/static`）。首次全量打包或独立启动后端前，**必须执行前端编译**，否则后端启动后访问 Web 管理界面将报 404 错误。

---

## 3 配合开发机（iMac 26.1 Colima）拉起中间件与流媒体

在后端启动之前，必须先在配合机上就绪数据库、缓存与流媒体服务。为便于统一纳管、参数调优与日志回溯，推荐采用 **合并的 `docker-compose.yml` 并外挂配置文件目录** 进行编排管理。

### 3.1 启动 Colima 运行环境与获取 IP

在 iMac 终端中检查并启动 Colima 容器守护进程：

```bash
# 启动 Colima（建议分配充足的 CPU 与内存资源）
colima start --cpu 4 --memory 4

# 查看当前 iMac 在局域网中的真实 IP 地址（请记下此 IP，例如 192.168.1.50）
ipconfig getifaddr en0
```

### 3.2 组织配置与挂载目录结构

在 iMac 上建立统一的中间件编排目录（例如 `~/wvp-infra`）：

```bash
mkdir -p ~/wvp-infra/{mysql/conf.d,mysql/initdb,redis,zlm}
cd ~/wvp-infra
```

规划目录层级如下：
```text
~/wvp-infra/
├── docker-compose.yml       # 统一编排文件
├── mysql/
│   ├── conf.d/my.cnf        # MySQL 字符集、大小写敏感等定制配置
│   └── initdb/init.sql      # 数据库初始化表结构脚本
├── redis/
│   └── redis.conf           # Redis 端口、网络绑定与密码配置
└── zlm/
    └── config.ini           # ZLMediaKit API Secret、端口与 WebRTC 配置
```

### 3.3 映射配置文件准备

#### 1. MySQL 定制配置 (`mysql/conf.d/my.cnf`)
```ini
[mysqld]
character-set-server=utf8mb4
collation-server=utf8mb4_general_ci
lower_case_table_names=1
default-time-zone=+08:00
max_connections=1000
```
将项目代码库中的建表脚本拷贝或软链接至 `mysql/initdb/init.sql`（对应项目中的 [数据库/2.7.4/初始化-mysql-2.7.4.sql](../../../数据库/2.7.4/初始化-mysql-2.7.4.sql)），容器首次创建时会自动执行建表与初始数据导入。

#### 2. Redis 配置文件 (`redis/redis.conf`)
```ini
bind 0.0.0.0
protected-mode no
port 6379
timeout 0
tcp-keepalive 300
appendonly yes
# 若需要密码（需与 application-dev.yml 中保持一致，如设为 luna）：
# requirepass luna
```

#### 3. ZLMediaKit 核心配置 (`zlm/config.ini`)
> [!IMPORTANT]
> **语音对讲为什么必须依赖 WebRTC？**  
> 国标双向语音对讲由前端 Web 浏览器采集麦克风音频，通过 WebRTC 协议推送到 ZLM，再由 ZLM 转封装为 PS/RTP 广播给摄像头；反向音频流同样经由 ZLM 解封装通过 WebRTC 拉流送回浏览器播放。因此，**若要使用语音对讲功能，ZLM 必须开启 WebRTC 支持**。官方 Docker 镜像已原生内置 WebRTC 模块。

在 `zlm/config.ini` 中重点配置 `api.secret`、HTTP 端口及 WebRTC 端口：
```ini
[api]
apiDebug=1
# 与 WVP 后端 application-dev.yml 中的 media.secret 严格一致
secret=035c73f7-bb6b-4889-a715-d9eb2d1925cc
snapRoot=./www/snap/
defaultSnap=./www/logo.png

[http]
port=80
sslport=443

[rtc]
# WebRTC 媒体协商与语音对讲核心端口 (UDP)
port=8000
tcpPort=8000

[rtp_proxy]
# RTP 国标收流多端口范围
port=10000
port_range=40000-40050
```

### 3.4 编写合并的 `docker-compose.yml`

在 `~/wvp-infra/docker-compose.yml` 中统一编排 MySQL 8.0、Redis 7.0 与 ZLMediaKit：

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

### 3.5 一键启动与日常管理命令

在 iMac 的 `~/wvp-infra` 目录下执行：

```bash
# 1. 一键后台拉起全部中间件服务
docker compose up -d

# 2. 查看各容器健康状态与端口映射
docker compose ps

# 3. 实时查看流媒体与对讲日志
docker compose logs -f wvp-zlm

# 4. 停止并释放容器（数据持久保存在 Docker Volume 中，不会丢失）
docker compose down
```

### 3.6 双机跨机通信与 Webhook 回调避坑铁律

当 ZLM 部署在 iMac 配合机而 WVP-PRO 部署在 Windows 11 主机时，存在双向通信流动：

1. **WVP -> ZLM 控制调用（RESTful API）**：
   - Windows 11 上的 WVP 会通过 HTTP 访问 iMac 的 `http://<iMac_IP>:9092/index/api/...` 进行拉流点播与推流关闭。
   - WVP 配置文件中 `media.ip` 必须填 **iMac 的局域网真实 IP**（严禁写 `127.0.0.1`）。
2. **ZLM -> WVP 事件回调（Webhook Hook）**：
   - 当设备开始向 ZLM 推流、断开流或客户端开始播放时，ZLM 会向 WVP 发送 HTTP Webhook 请求。
   - **关键避坑配置**：WVP 默认会自动配置 ZLM 的 hook 地址。在跨机部署时，**必须在 WVP 的配置中指定 `media.hook-ip: <Windows_11_IP>`**，通知 ZLM 将事件回调发往 Windows 11 主机的真实局域网 IP，否则 ZLM 会默认尝试回调 `127.0.0.1` 导致回调超时失效。
3. **API Secret 鉴权一致性**：
   - WVP 配置中的 `media.secret` 必须与 `zlm/config.ini` 中的 `secret`（默认密钥 `035c73f7-bb6b-4889-a715-d9eb2d1925cc`）保持严格一致。

---

## 4 主开发机（Windows 11）基础环境就绪

主开发机已具备 Git、Node.js（如 Node 18/20/24）及 Python 环境，只需确认并补齐 Java 21 与 Maven。

### 4.1 安装与验证 JDK 21 及 Maven

若尚未安装 Java 21 与 Maven，推荐在 Windows 11 终端（PowerShell）中通过自带的 `winget` 快速完成安装：

```powershell
# 1. 采用 winget 一键安装 Eclipse Adoptium Temurin 21 JDK 与 Apache Maven
winget install EclipseAdoptium.Temurin.21.JDK --accept-source-agreements --accept-package-agreements
winget install Apache.Maven --accept-source-agreements --accept-package-agreements

# 2. 重启终端以刷新系统环境变量，并验证版本
java -version
# 须输出 openjdk version "21.x.x"
mvn -version
# 须输出 Apache Maven 3.8+ 及 Java version 21
```

*(若手动下载解压安装，请确保将 JDK 根路径设为系统变量 `JAVA_HOME`，并将 `%JAVA_HOME%\bin` 与 Maven `bin` 路径追加至系统 `Path`。)*

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

### 6.1 获取并核对网络 IP

1. **配合机（iMac 26.1）IP**：通过 iMac 终端命令 `ipconfig getifaddr en0` 查看（假设为 `192.168.1.50`）；
2. **主开发机（Win 11）IP**：在 PowerShell 中执行 `ipconfig` 查看当前 Wi-Fi 或以太网局域网 IPv4 地址（假设为 `192.168.1.100`）。

### 6.2 调整本地开发配置

打开配置文件 [src/main/resources/application-dev.yml](../../../src/main/resources/application-dev.yml)，根据双机局域网 IP 更新关键项：

```yaml
spring:
  data:
    redis:
      # 指向 iMac 配合机的 Redis 容器
      host: 192.168.1.50
      port: 6379
      password: "" # 若容器未设密码可留空
  datasource:
    # 指向 iMac 配合机的 MySQL 8.0 容器
    url: jdbc:mysql://192.168.1.50:3306/wvp?useUnicode=true&characterEncoding=UTF8&rewriteBatchedStatements=true&serverTimezone=PRC&useSSL=false&allowMultiQueries=true&allowPublicKeyRetrieval=true
    username: root
    password: root

# 作为 28181 SIP 服务器的配置
sip:
  # 监听端口
  port: 8116
  # 若主开发机存在多个虚拟网卡，建议显式指定当前局域网真实 IP，置空则监听 0.0.0.0
  ip: 192.168.1.100

media:
  id: zlmediakit-local
  # 指向 iMac 配合机上运行的 ZLM 局域网 IP
  ip: 192.168.1.50
  http-port: 9092
  # [重点避坑] 明确告诉 ZLM 回调 Windows 11 主开发机的 IP 地址
  hook-ip: 192.168.1.100
  # 与 ZLM 容器中的 secret 严格匹配
  secret: 035c73f7-bb6b-4889-a715-d9eb2d1925cc
```

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
{
  "version": "0.2.0",
  "configurations": [
    {
      "type": "java",
      "name": "Debug WVP-PRO (Dev)",
      "request": "launch",
      "mainClass": "com.genersoft.wvp.vmanager.VManagerApplication",
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

## 8 双机协同网络与防火墙通信矩阵

双机协同、国标设备推拉流及 ONVIF 设备接入涉及的端口及数据流向关系如下，请对照检查两端网络通畅：

| 来源端 | 目标端 | 端口号 | 传输协议 | 作用说明 | 必选 |
|---|---|---|---|---|---|
| **国标摄像头 / 设备** | **主开发机 (Win 11)** | `8116` / `5060` | UDP & TCP | GB28181 SIP 信令注册、心跳与控制交互 | **是**（国标） |
| **ONVIF 摄像头 / 设备** | **主开发机 (Win 11)** | `3702` | UDP | WS-Discovery 局域网设备自发现（多播/单播应答） | **是**（ONVIF） |
| **主开发机 (Win 11)** | **ONVIF 摄像头 / 设备** | `80` / `8080` / `8899` | TCP | ONVIF SOAP 信令交互（设备信息、PTZ、Profile 等） | **是**（ONVIF） |
| **主开发机 (Win 11)** | **配合机 (iMac)** | `3306` | TCP | WVP 后端访问 MySQL 8.0 数据库 | **是** |
| **主开发机 (Win 11)** | **配合机 (iMac)** | `6379` | TCP | WVP 后端读写 Redis 状态与锁 | **是** |
| **主开发机 (Win 11)** | **配合机 (iMac)** | `9092` (HTTP) | TCP | WVP 向 ZLM 发送 RESTful 控制指令 | **是** |
| **配合机 (iMac: ZLM)**| **主开发机 (Win 11)** | `18080` (HTTP) | TCP | ZLM 向 WVP 发送流上下线 Webhook 回调（**易被 Win 防火墙拦截**） | **是** |
| **国标摄像头 / 设备** | **配合机 (iMac: ZLM)**| `40000~45000` | UDP & TCP | GB28181 摄像头推送 PS-RTP 音视频媒体流 | **是**（国标） |
| **配合机 (iMac: ZLM)**| **ONVIF 摄像头 / 设备** | `554` | TCP | ZLMediaKit 主动向摄像头拉取 RTSP 音视频流（出站拉流代理） | **是**（ONVIF） |
| **客户端浏览器** | **配合机 (iMac: ZLM)**| `8000` | UDP | WebRTC 语音对讲与超低延迟拉流 | **是**（对讲必选）|
| **客户端浏览器** | **主开发机 (Win 11)** | `18080` / `9528` | TCP | 访问 WVP 管理后台 Web 界面 | **是** |

### 8.1 防火墙放行实施细节

#### 1. Windows 11 主开发机防火墙与网络配置（PowerShell 管理员模式）

Windows 11 默认可能会将局域网识别为公用网络（Public），从而阻断来自配合机或摄像头的入站连接。建议按以下步骤进行设置：

- **确认网络连接类别**：
  ```powershell
  # 查看当前网络连接类别（Public 或 Private）
  Get-NetConnectionProfile

  # 若显示为 Public，建议将当前局域网适配器（例如 Wi-Fi 或以太网）切换为专用网络（Private）
  Set-NetConnectionProfile -InterfaceAlias "Wi-Fi" -NetworkCategory Private
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

#### 2. iMac 配合机防火墙与容器端口监听（macOS 终端）

- **macOS 系统内置防火墙**：
  ```bash
  # 检查 macOS 内置防火墙状态
  sudo /usr/libexec/ApplicationFirewall/socketfilterfw --getglobalstate
  ```
  若状态为已启用且局域网互通异常，可在「系统设置 -> 网络 -> 防火墙」中放开传入连接，或在联调测试期间临时关闭：
  ```bash
  sudo /usr/libexec/ApplicationFirewall/socketfilterfw --setglobalstate off
  ```

- **Colima 容器端口绑定验证**：
  Docker Compose 暴露的端口由 Colima 自动映射至 macOS 宿主网卡。可执行以下命令验证端口是否处于监听状态：
  ```bash
  # 验证 TCP 关键端口（MySQL 3306、Redis 6379、ZLM HTTP 9092）
  lsof -iTCP -sTCP:LISTEN -P -n | grep -E '3306|6379|9092'

  # 验证 UDP 媒体与对讲端口（WebRTC 8000、RTP 接收 40000+）
  lsof -iUDP -P -n | grep -E '8000|4000'
  ```

#### 3. 双向网络连通性验证

两端配置完成后，建议进行双向连通性测试以确保无静默拦截：

- **Windows 11 -> iMac 方向**（在 Windows PowerShell 中执行，确保中间件可达）：
  ```powershell
  Test-NetConnection -ComputerName 192.168.1.50 -Port 3306
  Test-NetConnection -ComputerName 192.168.1.50 -Port 9092
  ```

- **iMac -> Windows 11 方向**（在 iMac 终端中执行，确保 ZLM 回调无阻断）：
  ```bash
  # 在 Windows 启动 WVP 后端后，从 iMac 验证 18080 回调端口连通性
  nc -zv -G 3 192.168.1.100 18080
  # 或通过 curl 快速检测
  curl -I http://192.168.1.100:18080
  ```

---

## 附录：单机纯本地开发应急方案（外出备选）

若处于出差或脱离局域网配合机环境，主开发机可切换为纯单机独立轻量模式：

1. **轻量数据库（内置 H2 模式）**：
   打开 [src/main/resources/application-dev.yml](../../../src/main/resources/application-dev.yml)，注释 MySQL 数据源配置，取消注释内置 H2 数据库段（脚本位于 `数据库/2.7.4-h2/`），即可免装 MySQL 独立启动。
2. **本地流媒体服务**：
   访问 ZLMediaKit [官方 Release](https://github.com/ZLMediaKit/ZLMediaKit/issues/483) 下载 Windows 预编译压缩包，解压后双击运行 `MediaServer.exe`（官方发布包已内置 WebRTC 模块），并将 `media.ip` 与 `media.hook-ip` 均改回 `127.0.0.1`。

---

接下来请查阅：[服务详细配置](config.md) 了解具体配置项说明与国标高级参数调优。
