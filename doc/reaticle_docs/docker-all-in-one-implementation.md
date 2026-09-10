<!-- doc/reaticle_docs/docker-all-in-one-implementation.md -->

# WVP-PRO All-in-One 镜像双机协同编译与 Docker Hub 发布实施细则

## 0 事实、研判与前置契约 (Fact, Judgment & Contract)

### 0.1 已验证事实 (Fact)
1. **主开发机（Windows 11）环境**：拥有原生 JDK 21（[pom.xml](../../pom.xml#L63)）、Maven 3.8+、Node.js（>= 18，兼容 `--openssl-legacy-provider`）及 Maven/npm 本地完整依赖缓存；项目版本为 `2.7.4`，Maven 默认产物为 `target/wvp-pro-2.7.4.jar`。
2. **配合开发机（iMac 26.1）环境**：搭载 macOS 系统与 Colima 容器守护进程（Docker CLI），原生支持 Docker Buildx 容器化跨架构构建（[doc/reaticle_docs/compile.md](compile.md#L75)）。
3. **架构与系统依赖**：
   - 前端 Web（Vue CLI 4）与后端 Java 产物为平台无关资产（HTML/JS 静态文件与跨平台 JVM 字节码 Jar 包）。
   - 流媒体引擎 ZLMediaKit（C++20）与精简版 JRE 21（`jlink` 产物）具有高度 CPU 架构绑定性与 OS C 运行时（Alpine musl libc）绑定性，必须在目标系统架构下完成编译与模块剥离。
4. **数据库初始化脚本位置**：基准初始化建表脚本为 [数据库/2.7.4/初始化-mysql-2.7.4.sql](../../数据库/2.7.4/初始化-mysql-2.7.4.sql)，ONVIF 协议增量建表脚本为 [数据库/2.7.4/增量-onvif.sql](../../数据库/2.7.4/增量-onvif.sql)（包含 `wvp_onvif_device` 与 `wvp_onvif_channel` 表）。两者在同步前必须合并，以生成完整的 `init.sql`。

### 0.2 工程研判 (Judgment)
1. **双机职责分工最优解**：
   - **Windows 11 承担前置静态编译**：利用本机原生 CPU 与本地 Maven/npm 缓存完成前端 `npm run build:prod` 和后端 `mvn clean package`，单次打包仅需几十秒；彻底避免在 iMac 的 Docker 容器中拉取几百兆依赖，规避跨国网络下载波动与 QEMU 跨架构模拟编译的高额开销。
   - **iMac 承担容器拼装、C++ 编译与多架构 Hub 发布**：基于 Colima 提供的 Linux 容器环境，运行 `docker buildx` 进行 `linux/amd64` 与 `linux/arm64` 双架构交叉编译，并直接推送至 Docker Hub。
2. **资产同步极简主义**：
   - 严禁全量同步代码仓库（避免传输 `.git` 版本库、`web/node_modules` 海量小文件及临时缓存）；
   - 仅同步 **4 类最小资产集**：已编译的 `wvp-pro-2.7.4.jar`、Docker 构建资产（`docker/aio/` 目录）、数据库初始化 SQL 脚本（`init.sql`）以及构建触发脚本。
3. **行尾序列（Line Ending）防卫契约**：
   - Windows 平台生成的 Shell 脚本（特别是 `entrypoint.sh`）可能被 Git 或编辑器自动注入 `CRLF`（`\r\n`）行尾，在 Linux 容器中执行会直接引发 `/usr/local/bin/entrypoint.sh: line 2: $'\r': command not found` 致命崩溃。同步流程中必须强制转码为标准 `LF`。

### 0.3 未验证假设与边界防御 (Speculation)
- *[假设 1]*：iMac 配合机已在 macOS 系统设置中开启了「远程登录（SSH）」，且与 Windows 主开发机处于同一局域网内（或通过密钥完成 SSH 互信）。
- *[假设 2]*：维护人员已在 iMac 终端完成 `docker login` 并具备目标 Docker Hub 命名空间的写权限（如 `reaticle` 组织）。

---

## 1 整体架构与全生命周期流水线拓扑

```
+-------------------------------------------------------------------------------------------------------+
| 阶段一：Windows 11 主开发机 (Pre-Build & Sync)                                                         |
|                                                                                                       |
|  [web/]                         [src/main/resources/static]            [target/wvp-pro-2.7.4.jar]     |
|   Node.js 打包 (OpenSSL 3兼容) ====> 前端静态资源写入目录 ===============> Maven 生产打包 (跳过单元测试)       |
|                                                                                    ||                 |
|                                [scripts/sync-aio-to-imac.ps1]                      ||                 |
|  - 资产收集: jar + docker/aio/ + init.sql + LF换行净化 =============================+                 |
|  - 传输协议: SCP / SFTP / Rsync over OpenSSH                                                          |
+---------------------------------------------------+---------------------------------------------------+
                                                    |
                                       局域网网络传输 (LAN SSH)
                                                    |
                                                    v
+---------------------------------------------------+---------------------------------------------------+
| 阶段二：iMac 26.1 配合机 (Multi-Arch Build & Hub Publish)                                             |
|                                                                                                       |
|  工作目录: ~/wvp-aio-build/                                                                            |
|   ├── wvp.jar (来自 Windows)                                                                          |
|   ├── init.sql (来自 Windows)                                                                         |
|   └── docker/aio/ (来自 Windows)                                                                      |
|                                                                                                       |
|  [Colima Docker Daemon + Buildx: aio-builder]                                                         |
|   ├── Stage 1: jre-builder    (--platform=$TARGETPLATFORM) -> jlink 定制裁剪 ~48MB JRE                 |
|   ├── Stage 2: zlm-builder    (--platform=$TARGETPLATFORM) -> 编译 ZLMediaKit (WebRTC+SRTP) & strip   |
|   └── Stage 3: final-runner   (--platform=$TARGETPLATFORM) -> Alpine 3.19 + MariaDB + Redis + WVP     |
|                                                                                                       |
|  [Docker Hub 发布]                                                                                     |
|   └── docker buildx build --platform linux/amd64,linux/arm64 --push -t reaticle/wvp-pro-aio:2.7.4    |
+-------------------------------------------------------------------------------------------------------+
```

---

## 2 Windows 主开发机前置编译全流程

在 Windows 11 主机上，所有编译操作均通过 PowerShell（pwsh）完成。

### 2.1 依赖环境快速核验证

在项目根目录打开 PowerShell 终端，执行版本探测：

```powershell
# 1. 验证 JDK 21
java -version
# 预期输出: openjdk version "21.x.x"

# 2. 验证 Maven
mvn -version
# 预期输出: Apache Maven 3.8+ 及 Java version: 21

# 3. 验证 Node.js
node -v
# 预期输出: v18.x.x 或 v20.x.x 或更高
```

### 2.2 步骤一：编译 Vue Web 前端静态资源

由于 Node 17+ 默认切换为 OpenSSL 3.0，而 Vue CLI 4（Webpack 4）依赖 MD4 算法，必须注入环境变量后执行生产构建：

```powershell
# 1. 进入 web 目录
cd web

# 2. 注入 OpenSSL 3.0 兼容选项
$env:NODE_OPTIONS="--openssl-legacy-provider"

# 3. 执行生产构建
npm run build:prod

# 4. 验证产物归位
Test-Path ..\src\main\resources\static\index.html
# 输出为 True 即代表静态文件已正确写入 Spring Boot 资源目录
```

### 2.3 步骤二：打包 Spring Boot 后端可执行 Jar

返回仓库根目录，通过 Maven 执行生产构建，跳过测试用例以提升速度：

```powershell
# 1. 回到项目根目录
cd ..

# 2. 执行打包
mvn clean package -DskipTests

# 3. 检查并验证产物
Get-Item .\target\wvp-pro-2.7.4.jar | Select-Object Name, Length, LastWriteTime
# 预期生成大小约为 60MB~80MB 的独立 Spring Boot 可执行 Jar 包
```

---

## 3 双机资产同步实施方案与 Windows 自动化脚本

### 3.1 同步文件清单精确矩阵

| 序号 | 资产类别 | Windows 源路径 | iMac 远程目标路径 | 传输处理原则 |
|---|---|---|---|---|
| 1 | **后端核心可执行包** | `target/wvp-pro-2.7.4.jar` | `~/wvp-aio-build/wvp.jar` | 传输并标准化命名为 `wvp.jar` |
| 2 | **Docker 构建资产集** | `docker/aio/` | `~/wvp-aio-build/docker/aio/` | 包含 Dockerfile、entrypoint.sh、配置模板 |
| 3 | **数据库初始化脚本** | `数据库/2.7.4/初始化-mysql-2.7.4.sql`<br>+ `增量-onvif.sql` | `~/wvp-aio-build/init.sql` | 自动合并基础表与 ONVIF 增量表，标准化命名为 `init.sql` |
| 4 | **换行符净化动作** | `docker/aio/entrypoint.sh` | 同上 | **强制将 CRLF 转为 LF**，避免 Linux 解释器崩溃 |

> [!CAUTION]
> **绝对禁止同步的目录与文件清单：**
> - `.git/`（版本库历史，通常超过 100MB+）
> - `web/node_modules/`（数万个零散小文件，极度降低 SCP/Rsync 性能）
> - `target/classes/`、`target/generated-sources/`（中间编译产物）
> - `.idea/`、`.vscode/`、本地运行日志 `logs/`

### 3.2 iMac 配合机 SSH 接收端配置（一次性准备）

在 iMac 配合机上执行一次性设置：
1. 打开 **系统设置 -> 通用 -> 共享 -> 启用「远程登录 (Remote Login)」**；
2. 允许当前用户访问（假设 macOS 用户名为 `reaticle`）；
3. 获取 iMac 局域网 IP 地址：
   ```bash
   ipconfig getifaddr en0
   # 假定输出为: 192.168.1.50
   ```
4. （可选推荐）在 Windows 11 PowerShell 中配置免密登录：
   ```powershell
   # 若未生成过密钥，执行: ssh-keygen -t ed25519
   # 将公钥推送至 iMac（Windows 自带 ssh-copy-id 或手动写入）:
   type $env:USERPROFILE\.ssh\id_ed25519.pub | ssh reaticle@192.168.1.50 "mkdir -p ~/.ssh && cat >> ~/.ssh/authorized_keys"
   ```

### 3.3 编制 Windows 自动化同步脚本

在 Windows 主开发机上，创建标准同步脚本 `scripts/sync-aio-to-imac.ps1`：

```powershell
# scripts/sync-aio-to-imac.ps1
<#
.SYNOPSIS
    WVP All-in-One 镜像构建资产极速增量同步脚本 (Windows 11 -> iMac 配合机)
.DESCRIPTION
    1. 检查前端与后端打包产物完整性；
    2. 自动化将 entrypoint.sh 换行符转换为 LF；
    3. 通过 SCP / SSH 增量同步资产至 iMac 临时构建目录。
.PARAMETER iMacHost
    配合机 IP 地址，默认为 192.168.1.50
.PARAMETER iMacUser
    配合机 SSH 用户名，默认为 reaticle
.PARAMETER RemoteDir
    配合机远程工作空间，默认为 ~/wvp-aio-build
#>

[CmdletBinding()]
param(
    [string]$iMacHost = "192.168.1.50",
    [string]$iMacUser = "reaticle",
    [string]$RemoteDir = "~/wvp-aio-build",
    [int]$Port = 22
)

$ErrorActionPreference = "Stop"

# 1. 定位工程根目录
$ProjectRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
Set-Location $ProjectRoot
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "[Sync] WVP-PRO 资产同步流水线启动..." -ForegroundColor Cyan
Write-Host "[Sync] 工程根路径: $ProjectRoot" -ForegroundColor Gray
Write-Host "[Sync] 目标主机  : ${iMacUser}@${iMacHost}:${RemoteDir}" -ForegroundColor Gray
Write-Host "============================================================" -ForegroundColor Cyan

# 2. 前置构建产物检查
$JarPath = Join-Path $ProjectRoot "target\wvp-pro-2.7.4.jar"
if (-not (Test-Path $JarPath)) {
    Write-Error "未找到打包产物: $JarPath，请先执行: mvn clean package -DskipTests"
}

$AioDir = Join-Path $ProjectRoot "docker\aio"
if (-not (Test-Path $AioDir)) {
    Write-Error "未找到构建资产目录: $AioDir"
}

# 2.3 数据库脚本检查与 ONVIF 增量表结构智能合流
$BaseSqlPath = Join-Path $ProjectRoot "数据库\2.7.4\初始化-mysql-2.7.4.sql"
$OnvifSqlPath = Join-Path $ProjectRoot "数据库\2.7.4\增量-onvif.sql"

if (-not (Test-Path $BaseSqlPath)) {
    Write-Error "未找到数据库基础初始化脚本: $BaseSqlPath"
}

$TempSqlDir = Join-Path $ProjectRoot "target"
if (-not (Test-Path $TempSqlDir)) {
    New-Item -ItemType Directory -Path $TempSqlDir -Force | Out-Null
}
$CombinedSqlPath = Join-Path $TempSqlDir "init-combined.sql"

$BaseSql = [System.IO.File]::ReadAllText($BaseSqlPath)
if (Test-Path $OnvifSqlPath) {
    Write-Host "[Sync] 检测到 ONVIF 协议增量表结构 (增量-onvif.sql)，正在自动合流..." -ForegroundColor Green
    $OnvifSql = [System.IO.File]::ReadAllText($OnvifSqlPath)
    $CombinedSql = $BaseSql + "`n`n-- ==================== ONVIF INCREMENTAL TABLES ====================`n`n" + $OnvifSql
} else {
    $CombinedSql = $BaseSql
}
[System.IO.File]::WriteAllText($CombinedSqlPath, $CombinedSql, [System.Text.UTF8Encoding]::new($false))
$SqlPath = $CombinedSqlPath

# 3. 规避 CRLF 换行符隐患 (强制转换为标准 LF)
$EntrypointFile = Join-Path $AioDir "entrypoint.sh"
if (Test-Path $EntrypointFile) {
    Write-Host "[Sync] 正在对 entrypoint.sh 进行 LF 换行符净化..." -ForegroundColor Yellow
    $Content = [System.IO.File]::ReadAllText($EntrypointFile)
    $Content = $Content -replace "`r`n", "`n"
    [System.IO.File]::WriteAllText($EntrypointFile, $Content, [System.Text.UTF8Encoding]::new($false))
}

# 4. 在 iMac 端创建目标目录
Write-Host "[Sync] 正在检查并初始化 iMac 远程目录..." -ForegroundColor Yellow
$SshTarget = "${iMacUser}@${iMacHost}"
ssh -p $Port $SshTarget "mkdir -p $RemoteDir/docker/aio"
if ($LASTEXITCODE -ne 0) {
    Write-Error "无法通过 SSH 连接至 iMac (${SshTarget})，请检查网络或 SSH 服务状态。"
}

# 5. 执行极速同步
Write-Host "[Sync] 1/3 同步核心后端 Jar 包 (wvp.jar)..." -ForegroundColor Green
scp -P $Port $JarPath "${SshTarget}:${RemoteDir}/wvp.jar"

Write-Host "[Sync] 2/3 同步数据库初始化脚本 (基础表+ONVIF增量表合流至 init.sql)..." -ForegroundColor Green
scp -P $Port $SqlPath "${SshTarget}:${RemoteDir}/init.sql"

Write-Host "[Sync] 3/3 同步 Dockerfile 与编排资产 (docker/aio/)..." -ForegroundColor Green
scp -P $Port -r "${AioDir}/*" "${SshTarget}:${RemoteDir}/docker/aio/"

# 6. 赋予执行权限
Write-Host "[Sync] 修正远程 Shell 脚本可执行权限..." -ForegroundColor Yellow
ssh -p $Port $SshTarget "chmod +x $RemoteDir/docker/aio/*.sh 2>/dev/null || true"

Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "[Sync] 同步全部完成！请登录 iMac 配合机执行镜像打包发布。" -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan
```

### 3.4 执行同步

在 Windows PowerShell 中运行：

```powershell
.\scripts\sync-aio-to-imac.ps1 -iMacHost "192.168.1.50" -iMacUser "reaticle"
```

---

## 4 iMac 配合机构建资产规划与核心实现

为了确保 All-in-One 镜像在最小体积下稳定运行，以下全部构建资产统一组织并放置在 `docker/aio/` 目录下。

### 4.1 核心资产目录结构规范

```text
docker/aio/
├── Dockerfile                  # [核心 1] 多阶段跨平台极速加速构建文件
├── entrypoint.sh               # [核心 2] 容器 PID 1 进程监督、配置自愈与优雅停机引擎
├── build.sh                    # [核心 3] iMac 本地单键全自动多架构编译发布脚本
└── conf/                       # 默认出厂配置模板
    ├── application-aio.yml     # WVP 闭环配置模板
    ├── zlm-config.ini          # ZLMediaKit WebRTC 与流媒体配置
    └── redis-aio.conf          # Redis 轻量缓存配置
```

### 4.2 容器进程监督与优雅停机脚本 (`docker/aio/entrypoint.sh`)

生产级容器编排中，必须解决 **MariaDB / ZLM 脏数据与异常崩溃** 问题。以下脚本严格拦截 `SIGTERM` / `SIGINT`，并在停机时依序优雅关闭：

```bash
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

# 等待 ZLM HTTP 端口可用
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
```

### 4.3 闭环配置文件模板

#### 1. WVP 核心闭环配置 (`docker/aio/conf/application-aio.yml`)
```yaml
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
  # 容器内部闭环通信：ZLM -> WVP (绝无外部防火墙阻断问题)
  hook-ip: 127.0.0.1
  secret: 035c73f7-bb6b-4889-a715-d9eb2d1925cc
  auto-config: true
  rtp:
    enable: true
    port-range: 30000,30050
    send-port-range: 30000,30050

logging:
  file:
    path: /opt/wvp/logs
  level:
    root: INFO
    com.genersoft.wvp: INFO
```

#### 2. ZLMediaKit 配置模板 (`docker/aio/conf/zlm-config.ini`)
```ini
# docker/aio/conf/zlm-config.ini
[api]
apiDebug=0
secret=035c73f7-bb6b-4889-a715-d9eb2d1925cc
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
# WebRTC 媒体与对讲端口 (必须暴露 UDP 8000)
port=8000
tcpPort=8000

[rtp_proxy]
# 国标 RTP 收流端口池
port=10000
port_range=30000-30050

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
```

#### 3. Redis 轻量配置模板 (`docker/aio/conf/redis-aio.conf`)
```ini
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
```

### 4.4 预编译极速加速构建 Dockerfile (`docker/aio/Dockerfile.fast`)

> [!NOTE]
> - **双机同步极速模式（推荐）**：使用 `docker/aio/Dockerfile.fast`。依赖 Windows 主机同步的 `wvp.jar` 与 `init.sql`，在 iMac 上仅编译 C++ 流媒体引擎与定制 JRE，构建速度最快。
> - **单机全源码编译模式**：使用 `docker/aio/Dockerfile`。容器内包含 Node.js、Maven、C++ 全链路构建，自动合并基础表与 `增量-onvif.sql`。
> - `docker/aio/build.sh` 会自动探测上下文，若存在 `wvp.jar` 与 `init.sql` 则自动优先采用 `Dockerfile.fast`。

```dockerfile
# docker/aio/Dockerfile.fast

# ==============================================================================
# Stage 1: 基于目标平台生成微型定制 JRE (依赖 TARGETPLATFORM 保证指令集匹配)
# ==============================================================================
FROM --platform=$TARGETPLATFORM eclipse-temurin:21-jdk-alpine AS jre-builder

RUN echo "[Stage 1] 正在针对目标架构执行 JRE 21 模块定制剥离 (jlink)..." && \
    $JAVA_HOME/bin/jlink \
    --add-modules java.base,java.compiler,java.desktop,java.instrument,java.management,java.naming,java.net.http,java.prefs,java.rmi,java.scripting,java.security.jgss,java.security.sasl,java.sql,java.sql.rowset,java.transaction.xa,java.xml,jdk.crypto.cryptoki,jdk.crypto.ec,jdk.unsupported,jdk.management \
    --strip-debug \
    --no-man-pages \
    --no-header-files \
    --compress=2 \
    --output /opt/java-runtime

# ==============================================================================
# Stage 2: 目标架构编译 ZLMediaKit (针对 Alpine Linux musl libc 优化，使用 3.19 内置 libsrtp 2.5.0)
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
    coreutils

WORKDIR /build
# 拉取 ZLMediaKit 核心源码并更新子模块
RUN git clone --depth 1 https://gitee.com/xia-chu/ZLMediaKit.git && \
    cd ZLMediaKit && git submodule update --init --recursive --depth 1

WORKDIR /build/ZLMediaKit/build
# 开启 WebRTC、关闭无用测试项、采用 Release 构建 (限并发 -j2 规避 Colima OOM 崩溃)
RUN cmake .. \
    -DCMAKE_BUILD_TYPE=Release \
    -DENABLE_WEBRTC=ON \
    -DENABLE_TESTS=OFF \
    -DENABLE_API=ON \
    -DENABLE_SERVER=ON && \
    cmake --build . --target MediaServer -j2 && \
    strip --strip-all /build/ZLMediaKit/release/linux/Release/MediaServer

# ==============================================================================
# Stage 3: 最终精简生产运行底座 (Alpine Linux 3.19)
# ==============================================================================
FROM --platform=$TARGETPLATFORM alpine:3.19 AS final-runner

LABEL maintainer="reaticle <y.wang@reaticle.com>"
LABEL description="WVP-PRO All-in-One: Java 21 + Vue UI + ZLM + MariaDB + Redis"

ENV TZ=Asia/Shanghai \
    LANG=C.UTF-8 \
    JAVA_HOME=/opt/java-runtime \
    PATH=/opt/java-runtime/bin:/opt/media/bin:$PATH

# 1. 仅安装 Alpine 运行时依赖（剔除任何编译器）
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

# 3. 装配组件产物
# 3.1 拷入 JRE 21
COPY --from=jre-builder /opt/java-runtime /opt/java-runtime
# 3.2 拷入 ZLMediaKit 执行体与静态目录
COPY --from=zlm-builder /build/ZLMediaKit/release/linux/Release/MediaServer /opt/media/bin/MediaServer
COPY --from=zlm-builder /build/ZLMediaKit/release/linux/Release/default.pem /opt/media/bin/default.pem
COPY --from=zlm-builder /build/ZLMediaKit/www/ /opt/media/bin/www/

# 3.3 拷入 Windows 同步交付的业务产物
COPY wvp.jar /opt/wvp/wvp.jar
COPY init.sql /opt/wvp/init.sql

# 3.4 拷入出厂配置模板与守护引擎
COPY docker/aio/conf/application-aio.yml /opt/wvp/templates/application-aio.yml
COPY docker/aio/conf/zlm-config.ini /opt/wvp/templates/zlm-config.ini
COPY docker/aio/conf/redis-aio.conf /opt/wvp/templates/redis-aio.conf
COPY docker/aio/entrypoint.sh /usr/local/bin/entrypoint.sh

RUN chmod +x /usr/local/bin/entrypoint.sh /opt/media/bin/MediaServer

# 核心暴露端口声明
EXPOSE 18080/tcp 8116/tcp 8116/udp 3702/udp 9092/tcp 8000/udp 8000/tcp 1935/tcp 554/tcp 30000-30050/udp 30000-30050/tcp

VOLUME ["/opt/wvp/config", "/opt/media/conf", "/var/lib/mysql", "/opt/media/bin/www/record", "/opt/wvp/logs"]

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
```

---

## 5 iMac 配合机多架构编译与 Docker Hub 发布实操

登录进入 iMac 终端窗口，展开镜像编译与发布。

### 5.1 步骤一：启动 Colima 容器守护进程

为多架构构建分配充足的资源（建议 CPU $\ge$ 4 核，内存 $\ge$ 6GB，避免 C++ 编译触发 OOM 崩溃）：

```bash
# 启动 Colima
colima start --cpu 4 --memory 6 --disk 50

# 确认 Docker 客户端连接正常
docker info
```

### 5.2 步骤二：准备 Docker Buildx 多架构构建器

Docker 默认的构建实例无法跨平台输出多架构镜像列表，必须创建以 `docker-container` 为驱动的构建器：

```bash
# 1. 检查已有的 buildx 实例
docker buildx ls

# 2. 创建并切换至专用的 wvp-aio-builder 实例
docker buildx create --name wvp-aio-builder --driver docker-container --use

# 3. 初始化并拉取 QEMU 跨平台仿真器
docker buildx inspect --bootstrap
# 控制台输出中 Platforms 必须包含 linux/amd64 与 linux/arm64
```

### 5.3 步骤三：验证同步文件完整性

进入同步接收工作空间：

```bash
cd ~/wvp-aio-build

# 查看文件列表与权限
ls -la
# 必须包含: wvp.jar, init.sql, docker/
ls -la docker/aio/
# 必须包含: Dockerfile, entrypoint.sh, conf/
```

### 5.4 步骤四：本地单架构构建自测（冒烟测试）

在全面构建双架构并推送到 Docker Hub 之前，推荐在 iMac 本机架构（如 `linux/arm64`）上先构建并本地加载运行：

```bash
# 1. 本地单架构构建（使用 --load 注入本地镜像列表）
docker buildx build \
    --platform linux/arm64 \
    -t wvp-pro-aio:test \
    -f docker/aio/Dockerfile.fast \
    --load .

# 2. 检查本地镜像体积
docker images | grep wvp-pro-aio
# 验证 Virtual Size 是否低于 650MB

# 3. 快速启动冒烟测试容器
docker run -d --name wvp-test -p 18080:18080 -p 8116:8116/udp wvp-pro-aio:test

# 4. 实时观察 entrypoint.sh 自愈日志与四组件健康状态
docker logs -f wvp-test

# 5. 验证完毕后清理测试容器
docker stop wvp-test && docker rm wvp-test
```

### 5.5 步骤五：登录 Docker Hub 并执行全架构联合构建推送

> [!IMPORTANT]
> 多架构镜像（Multi-Arch Manifest List）底层无法在 Docker Daemon 单机存储引擎中直接合并共存，**必须通过 `--push` 参数直接编译并推送到远端注册库**。

1. **登录 Docker Hub**：
   ```bash
   # 输入用户名与 Docker Hub Access Token (或密码)
   docker login
   ```

2. **全自动编译并发布脚本 (`docker/aio/build.sh`)**：
   在 `~/wvp-aio-build/docker/aio/build.sh` 中固化以下脚本，方便后续持续迭代发布：

   ```bash
   #!/bin/bash
   # docker/aio/build.sh
   set -e

   # 基础参数配置
   DOCKER_USER="reaticle"
   IMAGE_NAME="wvp-pro-aio"
   VERSION="2.7.4"
   PLATFORMS="linux/amd64,linux/arm64"

   cd "$(dirname "$0")/../.." || exit 1
   echo "============================================================"
   echo "  开始构建 WVP-PRO All-in-One 多架构镜像并推送到 Docker Hub  "
   echo "  目标命名空间: ${DOCKER_USER}/${IMAGE_NAME}"
   echo "  发布版本版本: ${VERSION} 及 latest"
   echo "  目标指令集  : ${PLATFORMS}"
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

   # 激活构建器
   docker buildx use aio-builder 2>/dev/null || docker buildx create --name aio-builder --use

   # 执行多架构联合构建与远端推送
   docker buildx build \
       --platform "${PLATFORMS}" \
       -t "${DOCKER_USER}/${IMAGE_NAME}:${VERSION}" \
       -t "${DOCKER_USER}/${IMAGE_NAME}:latest" \
       -f "${DOCKERFILE}" \
       --push \
       .

   echo "============================================================"
   echo "  镜像构建并推送成功！正在核验远端 Manifest List..."
   echo "============================================================"
   docker buildx imagetools inspect "${DOCKER_USER}/${IMAGE_NAME}:${VERSION}"
   ```

3. **执行发布**：
   ```bash
   chmod +x docker/aio/build.sh
   ./docker/aio/build.sh
   ```

### 5.6 步骤六：Docker Hub 远端校验

构建完成后，执行 `imagetools inspect` 查看 Hub 上的元数据：

```bash
docker buildx imagetools inspect reaticle/wvp-pro-aio:2.7.4
```

预期输出摘要：
```text
Name:      docker.io/reaticle/wvp-pro-aio:2.7.4
MediaType: application/vnd.docker.distribution.manifest.list.v2+json
Signatures: none

Manifests:
  Platform:   linux/amd64
  MediaType:  application/vnd.docker.distribution.manifest.v2+json
  Size:       ...
  Digest:     sha256:4a7b...

  Platform:   linux/arm64
  MediaType:  application/vnd.docker.distribution.manifest.v2+json
  Size:       ...
  Digest:     sha256:9c1d...
```

---

## 6 Docker Hub 镜像说明与快速消费指南 (Docker Hub Overview & Quickstart)

本节汇总发布于 Docker Hub 的 `reaticle/wvp-pro-aio` 镜像元数据、官方主页描述模版、消费端拉取与一键部署命令，供外部使用者、运维工程师与自动化流水线直接查阅引用。

### 6.1 镜像仓库概览与多架构标签体系

- **官方 Docker Hub 仓库**：[`reaticle/wvp-pro-aio`](https://hub.docker.com/r/reaticle/wvp-pro-aio)
- **多架构支持**：依托 OCI Multi-Arch Manifest List 规范，发布单一镜像标签，Docker 客户端在拉取时自动适配宿主 CPU 架构：
  - `linux/amd64`：适用于 Intel / AMD 64 位 x86_64 服务器及云主机；
  - `linux/arm64`：适用于 Apple Silicon (M1/M2/M3/M4 系列)、华为鲲鹏、飞腾等 aarch64 边缘计算与工控设备。
- **发布标签（Tags）策略**：
  - `reaticle/wvp-pro-aio:2.7.4`：锁定 v2.7.4 生产稳定版本（推荐用于生产锁定）；
  - `reaticle/wvp-pro-aio:latest`：指向当前最新正式版本。
- **全栈内嵌核心引擎一览**：
  - **WVP-PRO 2.7.4**：基于 Spring Boot 3.4.4 + JDK 21（jlink 极简运行时）+ 内置 Vue Web 控制台，支持 GB28181-2016/2022 信令交互与 ONVIF 设备管理；
  - **ZLMediaKit (Release)**：C++20 高性能流媒体服务器，完整开启 WebRTC 对讲、SRTP、RTSP、RTMP、HTTP-FLV、HLS 协议支持；
  - **Alpine MariaDB 10.11+**：兼容 MySQL 8.0 语法规范，内置 2.7.4 基础表结构与 `wvp_onvif_*` 增量表，启动时自动初始化建表；
  - **Redis 7.x**：轻量级内存高速缓存与 SIP 事务分布式锁引擎。
- **体积极限脱水指标**：
  - Docker Hub 压缩下载体积：**$\le$ 250 MB**（仅需数十秒即可完成全栈网络拉取）；
  - 本地解压运行时占用：**$\approx$ 580 MB**（相较分立容器部署节省 60% 以上磁盘开销）；
  - 容器初始内存驻留：**$\approx$ 380 MB ~ 500 MB**。

---

### 6.2 Docker Hub 镜像主页描述规范模版 (README Template)

以下内容为专为 Docker Hub 镜像主页（Repository Overview / README）设计的纯 Markdown 说明文档。镜像维护者可在 Docker Hub 仓库设置中直接粘贴使用：

````markdown
# WVP-PRO All-in-One Multi-Arch Docker Image

[![Docker Pulls](https://img.shields.io/docker/pulls/reaticle/wvp-pro-aio.svg)](https://hub.docker.com/r/reaticle/wvp-pro-aio)
[![Docker Image Size](https://img.shields.io/docker/image-size/reaticle/wvp-pro-aio/2.7.4)](https://hub.docker.com/r/reaticle/wvp-pro-aio)
[![Supported Platforms](https://img.shields.io/badge/platform-linux%2Famd64%20%7C%20linux%2Farm64-blue.svg)](https://hub.docker.com/r/reaticle/wvp-pro-aio)
[![License](https://img.shields.io/badge/license-Apache%202.0-green.svg)](https://github.com/648540858/wvp-GB28181-pro)

WVP-PRO All-in-One 是面向 GB28181-2016/2022 国标视频平台与 ONVIF 监控设备的生产级一体化单容器镜像。将 **WVP-PRO 后端**、**Vue Web 前端**、**ZLMediaKit 流媒体引擎**、**MariaDB (MySQL兼容)** 与 **Redis** 深度融合为单一极简镜像，下载仅 ~250MB，实现开箱即用、一键启停与极速运维。

---

## 核心特性

- **四合一全栈闭环**：单容器内集成信令、流媒体、数据库与缓存，组件间内部环回互通（127.0.0.1），杜绝传统微服务多容器编排时的网络不通、防火墙拦截与配置失步痛点；
- **双架构原生适配**：原生支持 `linux/amd64` 与 `linux/arm64`，无缝兼容 x86 云服务器与 ARM 边缘工控机/树莓派/Apple Silicon；
- **配置与数据自愈**：首次启动若未挂载外部配置，自动注入出厂默认模板；数据目录为空时自动灌入最新合流 SQL 库表；
- **优雅停机与数据安全**：内置 POSIX 信号监督引擎，拦截 `SIGTERM` 并依序优雅退出各组件，彻底规避数据库损坏与录像切片丢失；
- **协议全面支持**：GB28181-2016/2022、ONVIF Profile S/T (WS-Discovery)、WebRTC 双向语音对讲、RTSP、RTMP、HTTP-FLV、HLS、WS-FLV。

---

## 快速上手 (Quickstart)

### 方式一：快速体验单行命令 (Bridge 模式，零配置即开即用)

无需预先准备任何配置文件与数据库，执行单条命令即可拉起全部服务：

```bash
docker run -d \
  --name wvp-aio \
  -p 18080:18080 \
  -p 8116:8116/udp \
  -p 8116:8116/tcp \
  -p 3702:3702/udp \
  -p 9092:9092 \
  -p 8000:8000/udp \
  -p 8000:8000/tcp \
  -p 1935:1935 \
  -p 554:554 \
  -p 30000-30050:30000-30050/udp \
  -p 30000-30050:30000-30050/tcp \
  reaticle/wvp-pro-aio:2.7.4
```

启动完成后，使用浏览器访问控制台：
- **Web 控制台地址**：`http://<宿主机IP>:18080`
- **默认登录账号**：`admin`
- **默认登录密码**：`admin`

---

### 方式二：生产网络直通模式 (`--net=host`，Linux 强烈推荐)

> [!TIP]
> 在 Linux 物理服务器上，强烈推荐使用 `--net=host` 模式：
> 1. **ONVIF 组播搜索**：WS-Discovery 基于 UDP `239.255.255.250:3702` 组播，Bridge 网桥会丢弃组播回包，只有 Host 模式可自动扫描发现局域网摄像头；
> 2. **RTP 媒体流吞吐**：免去数百个动态 RTP 端口在 Docker iptables 中的 NAT 映射开销，显著降低网络转发延迟与 CPU 软中断。

```bash
docker run -d \
  --name wvp-aio \
  --net=host \
  --restart=always \
  -v /data/wvp-aio/mysql:/var/lib/mysql \
  -v /data/wvp-aio/record:/opt/media/bin/www/record \
  -v /data/wvp-aio/logs:/opt/wvp/logs \
  reaticle/wvp-pro-aio:2.7.4
```

---

### 方式三：Docker Compose 持久化部署 (推荐生产管理)

创建 `docker-compose.yml` 文件：

```yaml
version: '3.8'

services:
  wvp-aio:
    image: reaticle/wvp-pro-aio:2.7.4
    container_name: wvp-aio
    restart: always
    environment:
      TZ: Asia/Shanghai
    volumes:
      # 持久化数据库（容器销毁数据物理保留）
      - ./data/mysql:/var/lib/mysql
      # 录像切片存储
      - ./data/record:/opt/media/bin/www/record
      # 平台运行日志
      - ./logs/wvp:/opt/wvp/logs
      - ./logs/media:/opt/media/log
      # （可选）若需深度自定义配置，可挂载外部文件（未挂载时自动使用出厂默认值）
      # - ./config/application.yml:/opt/wvp/config/application.yml
      # - ./config/zlm.ini:/opt/media/conf/config.ini
      # - ./config/redis.conf:/etc/redis.conf
    # Linux 裸机生产环境建议直接开启 host 模式：
    # network_mode: host
    # 若在 macOS / Windows 或云虚拟网卡环境下，启用显式端口映射：
    ports:
      - "18080:18080/tcp"              # WVP Web控制台与REST API
      - "8116:8116/udp"                # GB28181 SIP信令 (UDP)
      - "8116:8116/tcp"                # GB28181 SIP信令 (TCP)
      - "3702:3702/udp"                # ONVIF WS-Discovery设备发现
      - "9092:9092/tcp"                # ZLM HTTP-FLV / HLS 点播
      - "8000:8000/udp"                # WebRTC 媒体协商与语音对讲
      - "8000:8000/tcp"                # WebRTC TCP备用
      - "1935:1935/tcp"                # RTMP 推拉流
      - "554:554/tcp"                  # RTSP 推拉流
      - "30000-30050:30000-30050/udp"  # GB28181 RTP媒体接收端口池
      - "30000-30050:30000-30050/tcp"  # GB28181 RTP TCP媒体接收端口池
```

启动命令：
```bash
docker compose up -d
docker compose logs -f
```

---

## 核心配置与默认凭据

| 组件 / 服务 | 协议 / 端口 | 默认账号 / 口令 | 说明 |
|---|---|---|---|
| **WVP Web 控制台** | HTTP / 18080 | `admin` / `admin` | 国标平台管理、通道配置、分屏播放、语音对讲、ONVIF 控制 |
| **GB28181 SIP 服务** | UDP+TCP / 8116 | ID: `41010500002000000001`<br>密码: `admin` | 摄像头或下级平台级联接入使用的国标 SIP 服务信息 |
| **ZLMediaKit 流媒体** | HTTP / 9092 | Secret: `035c73f7-bb6b-4889-a715-d9eb2d1925cc` | 流媒体核心 REST API 与 Webhook 鉴权密钥 |
| **WebRTC 对讲** | UDP / 8000 | - | 网页端无插件低延迟直播与双向语音对讲 |
| **ONVIF 发现** | UDP / 3702 | - | WS-Discovery 组播搜索（Host 模式无缝生效） |
| **MariaDB 数据库** | TCP / 3306 | `root` / *(无密码)* | 内置直连，库名 `wvp`；出厂已初始化全部表 |
| **Redis 缓存** | TCP / 6379 | *(无密码)* | 内置环回直连，用于缓存与分布式锁 |
````

---

### 6.3 消费端极速部署与运行示例 (Quickstart Commands)

根据不同运行场景，使用者可灵活选择以下部署策略：

#### 场景 1：无宿主文件挂载的一键极速验证
此模式适用于快速了解 WVP 界面操作与 SIP 设备注册：
```bash
docker run -d \
  --name wvp-aio \
  -p 18080:18080 \
  -p 8116:8116/udp \
  -p 8116:8116/tcp \
  -p 3702:3702/udp \
  -p 9092:9092 \
  -p 8000:8000/udp \
  -p 8000:8000/tcp \
  -p 1935:1935 \
  -p 554:554 \
  -p 30000-30050:30000-30050/udp \
  -p 30000-30050:30000-30050/tcp \
  reaticle/wvp-pro-aio:2.7.4
```
容器在启动时会自动检测配置与数据库目录，通过 `entrypoint.sh` 自动生成全套数据，启动后 15 秒内即可访问 `http://<IP>:18080`。

#### 场景 2：Linux 裸机生产网络直通 (`--net=host`)
```bash
# 创建持久化存储目录
mkdir -p /data/wvp-aio/{mysql,record,logs}

# 直通启动
docker run -d \
  --name wvp-aio \
  --net=host \
  --restart=always \
  -v /data/wvp-aio/mysql:/var/lib/mysql \
  -v /data/wvp-aio/record:/opt/media/bin/www/record \
  -v /data/wvp-aio/logs:/opt/wvp/logs \
  reaticle/wvp-pro-aio:2.7.4
```

#### 场景 3：Docker Compose 工业级持久化工程化管理
使用项目附带的 [docker/docker-compose.aio.yml](../../docker/docker-compose.aio.yml)：
```bash
# 1. 运行工作区准备脚本 (自动建立目录并预设配置模板)
./docker/aio/setup-workspace.sh ./aio-data

# 2. 启动服务
docker compose -f docker/docker-compose.aio.yml up -d

# 3. 监控启动健康状态
docker compose -f docker/docker-compose.aio.yml logs -f
```

---

### 6.4 外部持久化挂载卷与配置热覆盖机制

镜像内定义了完善的自愈注入（Self-Healing）逻辑，支持运维人员在宿主机进行持久化与定制覆盖：

| 容器内挂载点 | 宿主机推荐路径 | 类别 | 读写属性 | 行为与自愈说明 |
|---|---|---|---|---|
| `/var/lib/mysql` | `./aio-data/data/mysql` | 数据存储 | 读写 (RW) | **核心数据库持久化**。若为空，容器自动执行首次初始化并灌入 `init.sql`；若已存在则直接挂载，历史数据绝对不丢。 |
| `/opt/media/bin/www/record` | `./aio-data/data/record` | 媒体文件 | 读写 (RW) | **录像与切片持久化**。ZLMediaKit 生成的 MP4 录像与 HLS 切片均保存在此。 |
| `/opt/wvp/logs` | `./aio-data/logs/wvp` | 系统日志 | 读写 (RW) | WVP-PRO Spring Boot 滚动业务日志。 |
| `/opt/media/log` | `./aio-data/logs/media` | 引擎日志 | 读写 (RW) | ZLMediaKit C++ 核心日志。 |
| `/opt/wvp/config/application.yml` | `./aio-data/config/application.yml` | 配置文件 | 只读/读写 | WVP 核心配置。未挂载时自动注入出厂默认模板。 |
| `/opt/media/conf/config.ini` | `./aio-data/config/zlm.ini` | 配置文件 | 只读/读写 | ZLM 流媒体配置。未挂载时自动注入出厂默认模板。 |
| `/etc/redis.conf` | `./aio-data/config/redis.conf` | 配置文件 | 只读/读写 | Redis 配置。未挂载时自动注入出厂默认模板。 |

---

### 6.5 核心暴露端口与网络拓扑矩阵

| 端口号 | 传输层 | 组件 | 功能用途 | Bridge 映射需求 | Host 模式优势 |
|---|---|---|---|---|---|
| `18080` | TCP | WVP-PRO | Web 管理控制台与前后端 REST API | 必选映射 `-p 18080:18080` | 直接监听物理网卡 |
| `8116` | UDP / TCP | WVP-PRO | GB28181 SIP 信令接入（摄像头注册/心跳/邀请） | 必选映射 `-p 8116:8116/udp -p 8116:8116/tcp` | 规避 NAT 引起的 SIP Via 头域 IP 错位 |
| `3702` | UDP | WVP-PRO | ONVIF WS-Discovery 局域网摄像头自动探测 | 映射 `-p 3702:3702/udp` | **仅 Host 模式支持组播接收**；Bridge 模式需手动添加 IP |
| `9092` | TCP | ZLMediaKit | HTTP-FLV / HLS / TS 直播播放及 RESTful 接口 | 必选映射 `-p 9092:9092` | 直连无代理损耗 |
| `8000` | UDP / TCP | ZLMediaKit | WebRTC 媒体流分发与浏览器双向语音对讲 | 必选映射 `-p 8000:8000/udp` | 显著降低音视频对讲首包延迟 |
| `1935` | TCP / UDP | ZLMediaKit | RTMP 推流与拉流播放 | 可选映射 `-p 1935:1935` | - |
| `554` | TCP / UDP | ZLMediaKit | RTSP 推流与拉流播放 | 可选映射 `-p 554:554` | - |
| `30000-30050` | UDP / TCP | ZLMediaKit | GB28181 国标 RTP 收流端口池 | 映射 `-p 30000-30050:30000-30050/udp` | **消除海量 iptables NAT 规则**，防软中断雪崩 |
| `3306` | TCP | MariaDB | 数据库直连（调试维护） | 仅在需宿主 Navicat 直连时暴露 | - |
| `6379` | TCP | Redis | 缓存直连（调试维护） | 仅在需外部调试时暴露 | - |

---

### 6.6 出厂默认凭据与服务入口速查表

| 入口类型 | 地址 / 凭据参数 | 默认值 | 权限与用途 |
|---|---|---|---|
| **Web 登录地址** | `http://<宿主机IP>:18080` | - | 系统统一 Web 交互控制中心 |
| **Web 默认账号** | 用户名 / 密码 | `admin` / `admin` | 超级管理员全权凭证 |
| **SIP 服务器 ID** | `sip.id` | `41010500002000000001` | 摄像头配置中的「SIP 服务器国标编码」 |
| **SIP 服务器域** | `sip.domain` | `4101050000` | 摄像头配置中的「SIP 域」 |
| **SIP 接入密码** | `sip.password` | `admin` | 摄像头配置中的「SIP 接入密码」 |
| **ZLM API Secret** | `media.secret` | `035c73f7-bb6b-4889-a715-d9eb2d1925cc` | WVP 联动 ZLM 的全局身份鉴权码 |
| **MariaDB 数据库** | Host / Port / User / Pwd | `127.0.0.1:3306` / `root` / *(无密码)* | 数据库直连，默认管理库名为 `wvp` |
| **Redis 缓存** | Host / Port / Pwd | `127.0.0.1:6379` / *(无密码)* | 内部环回缓存直连 |

---

## 7 常见避坑指南与故障排查矩阵 (Troubleshooting)

### 7.1 Windows 同步换行符导致容器 Entrypoint 崩溃
- **现象**：容器启动即报 `/usr/local/bin/entrypoint.sh: line 2: $'\r': command not found`。
- **根因**：Windows PowerShell / Git 默认可能以 CRLF 保存脚本。
- **解法**：在 `scripts/sync-aio-to-imac.ps1` 中已内置换行符过滤；若仍出现，可在 iMac 执行 `dos2unix docker/aio/entrypoint.sh` 或在 Dockerfile 中通过 `sed -i 's/\r$//' /usr/local/bin/entrypoint.sh` 防御。

### 7.2 Colima 跨架构 C++ 编译 OOM 崩溃
- **现象**：构建 `zlm-builder` 阶段执行 `cmake --build .` 时抛出 `g++: fatal error: Killed signal terminated program cc1plus`。
- **根因**：Colima 虚拟机默认内存仅 2GB，并发多核编译消耗耗尽内存。
- **解法**：启动 Colima 时显式分配 6GB 以上内存：`colima start --cpu 4 --memory 6`。

### 7.3 MariaDB 表名大小写敏感导致 MyBatis 报错
- **现象**：容器运行正常，但访问 Web 页面提示 `Table 'wvp.WVP_DEVICE' doesn't exist`。
- **根因**：Linux 下 MariaDB 默认大小写敏感（`lower_case_table_names=0`），而建表脚本或代码映射混用了大小写。
- **解法**：在首次 `mysql_install_db` 和 `mysqld --bootstrap` 初始化阶段，**必须强制指定 `--lower-case-table-names=1`**。一旦数据目录生成后，不可随意切换，否则库表字典损坏。

### 7.4 WebRTC 对讲声音无法建立连接
- **现象**：视频播放正常，但点击对讲无法听到声音，对讲状态处于协商中。
- **根因**：WebRTC 依赖 UDP 8000 端口，且部分浏览器安全策略禁止在非 HTTPS（除 `localhost` 外）环境下打开麦克风。
- **解法**：
  1. 确保 Docker 运行参数或 Compose 模板中暴露了 `8000:8000/udp`；
  2. 远端客户端访问需通过 HTTPS 反向代理，或在 Chrome 访问 `chrome://flags/#unsafely-treat-insecure-origin-as-secure` 将 `http://<Host-IP>:18080` 加入白名单以授权采集麦克风。

### 7.5 Docker 容器内点击 ONVIF 设备搜寻无法找到局域网设备
- **现象**：在 Web 页面点击“搜索设备”，进度条走完后列表为空，但局域网内确实存在正常在线的 ONVIF 摄像头。
- **根因**：WS-Discovery 依赖发送至 `239.255.255.250:3702` 的 UDP 组播。Docker 默认 Bridge 桥接网络（如 `docker0`）会丢弃组播报文，使得摄像头无法接收到 Probe 探测或回包无法穿透网桥进入容器。
- **解法**：
  1. **生产环境（Linux 裸机）**：在 `docker-compose.yml` 中声明 `network_mode: host`，使容器共享宿主机物理网络栈，即可恢复组播发现；
  2. **开发/跨平台环境（macOS / Windows Bridge 模式）**：受轻量级虚拟机网络限制无法使用 host 模式，此时请使用 Web 界面的“手动添加”功能，直接输入摄像机的实际 IP 与 ONVIF 端口（如 80/8080/8899）即可完成无缝接入。

---

## 8 交付验证检查表 (Verification Checklist)

| 阶段 | 序号 | 检查项 | 验证命令 / 判据 | 状态 |
|---|---|---|---|---|
| **Windows 前置** | 1 | 前端编译产物归位 | `Test-Path .\src\main\resources\static\index.html` 必须为 True | [ ] |
| | 2 | 后端 Jar 成功生成 | `target\wvp-pro-2.7.4.jar` 大小在 60MB~80MB 之间 | [ ] |
| | 3 | 数据库合流产物生成 | `target\init-combined.sql` 包含 `wvp_onvif_device` 表结构 | [ ] |
| **双机同步** | 4 | SSH 免密与文件推送 | `.\scripts\sync-aio-to-imac.ps1` 执行无退出码异常 | [ ] |
| | 5 | 脚本换行符净化 | iMac 执行 `file ~/wvp-aio-build/docker/aio/entrypoint.sh` 显示 `ASCII text, with LF line terminators` | [ ] |
| **iMac 构建** | 6 | Buildx 实例处于活动状态 | `docker buildx ls` 显示 `aio-builder *` | [ ] |
| | 7 | 本地单架构冒烟成功 | `docker run` 容器后，四服务端口及 3702/udp 在 20 秒内全部绿灯就绪 | [ ] |
| **Hub 发布** | 8 | 多架构联合推送完毕 | `docker buildx build --push` 返回成功 | [ ] |
| | 9 | 远端 Manifest 包含双架构 | `imagetools inspect` 同时包含 `linux/amd64` 与 `linux/arm64` | [ ] |
| | 10 | 镜像体积达标 | Docker Hub Compressed Download Size $\le$ 280MB | [ ] |
| **Hub 消费文档** | 11 | Docker Hub Readme 齐备 | 包含平台支持、极速一键运行、默认凭据与挂载卷矩阵 | [ ] |
| | 12 | 远端拉取消费冒烟 | `docker run --rm reaticle/wvp-pro-aio:2.7.4` 无挂载模式 15 秒内自愈就绪 | [ ] |

