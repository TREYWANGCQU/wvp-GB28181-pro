# scripts/generate-wiki.ps1
<#
.SYNOPSIS
    WVP-PRO GitHub Wiki 自动化生成与同步发布脚本
.DESCRIPTION
    依据 doc/reaticle_docs/ 目录下的高质量工程文档：
    1. 自动转换相对文件引用与锚点至 GitHub Wiki 兼容链接；
    2. 将代码仓相对路径转化为标准的 GitHub 源码预览链接；
    3. 自动生成全局导航大纲与门户首页（Home.md, _Sidebar.md, _Footer.md）；
    4. 复制并重构为 Wiki 扁平化命名体系；
    5. 支持一键自动提交并推送至 GitHub Wiki 远端仓库。
.PARAMETER WikiDir
    Wiki 本地克隆目录，默认优先检测 ../wvp-GB28181-pro.wiki
.PARAMETER Push
    是否在生成后直接执行 git commit 与 git push
.PARAMETER CommitMessage
    提交信息，默认为 'feat(wiki): automated sync from doc/reaticle_docs'
#>

[CmdletBinding()]
param(
    [string]$WikiDir = "",
    [switch]$Push,
    [string]$CommitMessage = "feat(wiki): initialize wvp-pro wiki from doc/reaticle_docs"
)

$ErrorActionPreference = "Stop"

# 1. 路径与环境就绪检查
$ProjectRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$DocsRoot = Join-Path $ProjectRoot "doc\reaticle_docs"

if (-not (Test-Path $DocsRoot)) {
    Write-Error "文档源目录不存在: $DocsRoot"
}

if ([string]::IsNullOrWhiteSpace($WikiDir)) {
    $SiblingWiki = Join-Path $ProjectRoot "..\wvp-GB28181-pro.wiki"
    if (Test-Path $SiblingWiki) {
        $WikiDir = (Resolve-Path $SiblingWiki).Path
    }
    else {
        $WikiDir = Join-Path $ProjectRoot ".wiki"
    }
}

if (-not (Test-Path $WikiDir)) {
    Write-Host "[Wiki] 目标 Wiki 目录不存在，准备从 GitHub 克隆..." -ForegroundColor Yellow
    $WikiRemote = "git@github.com:TREYWANGCQU/wvp-GB28181-pro.wiki.git"
    git clone $WikiRemote $WikiDir
    if ($LASTEXITCODE -ne 0) {
        Write-Error "克隆 Wiki 仓库失败: $WikiRemote"
    }
}

Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "[Wiki] GitHub Wiki 自动化构建发布引擎启动..." -ForegroundColor Cyan
Write-Host "[Wiki] 文档源路径: $DocsRoot" -ForegroundColor Gray
Write-Host "[Wiki] Wiki 工作区: $WikiDir" -ForegroundColor Gray
Write-Host "============================================================" -ForegroundColor Cyan

# 2. 文档映射矩阵定义
$DocMappings = @(
    @{
        Source      = "compile.md"
        Target      = "Compile-and-Dev-Guide.md"
        Title       = "编译与开发指南"
        Category    = "开发与编译"
        Description = "三机分工架构、Windows 11 开发环境、前端/后端编译及测试机离线部署全指南"
    },
    @{
        Source      = "docker-all-in-one-solution.md"
        Target      = "Docker-All-in-One-Solution.md"
        Title       = "All-in-One 镜像合并打包架构方案"
        Category    = "Docker 全栈交付"
        Description = "WVP-PRO + ZLM + MySQL + Redis 四合一极速交付架构规范、硬约束与 WBS"
    },
    @{
        Source      = "docker-all-in-one-implementation.md"
        Target      = "Docker-All-in-One-Implementation.md"
        Title       = "All-in-One 双机编译与 Hub 发布细则"
        Category    = "Docker 全栈交付"
        Description = "跨平台多架构（amd64/arm64）构建、自愈 Entrypoint、Docker Hub 发布与全量运行指南"
    },
    @{
        Source      = "onvif-support-solution.md"
        Target      = "ONVIF-Support-Solution.md"
        Title       = "ONVIF 协议支持技术实施方案"
        Category    = "ONVIF 协议工程"
        Description = "原生轻量自研 SOAP 引擎、WS-Discovery 探测、Profile 解析及 PTZ 云台控制技术体系"
    },
    @{
        Source      = "onvif-implementation/README.md"
        Target      = "ONVIF-Implementation-Guide.md"
        Title       = "ONVIF 协议支持五阶段实施细节工程指南"
        Category    = "ONVIF 协议工程"
        Description = "五阶段递进实施架构全景、类图结构、依赖约束与交付验收门禁"
    },
    @{
        Source      = "onvif-implementation/phase-1-soap-engine-and-discovery.md"
        Target      = "ONVIF-Phase-1-SOAP-Engine-and-Discovery.md"
        Title       = "Phase 1: 通信底座与网络探测"
        Category    = "ONVIF 协议工程"
        Description = "JDK 21 原生 HttpClient SOAP 客户端、时钟偏斜自动补偿与 WS-Discovery 探测器"
    },
    @{
        Source      = "onvif-implementation/phase-2-persistence-and-channel-sync.md"
        Target      = "ONVIF-Phase-2-Persistence-and-Channel-Sync.md"
        Title       = "Phase 2: 持久化与通道同步"
        Category    = "ONVIF 协议工程"
        Description = "增量 DDL 设计、OnvifDevice 实体模型、码流解析与 GBChannel (data_type=4) 挂接"
    },
    @{
        Source      = "onvif-implementation/phase-3-media-proxy-and-ptz-control.md"
        Target      = "ONVIF-Phase-3-Media-Proxy-and-PTZ-Control.md"
        Title       = "Phase 3: 媒体调度与云台控制"
        Category    = "ONVIF 协议工程"
        Description = "ZLM StreamProxy 拉流接管、WebRTC/HTTP-FLV 分发、无人观看自动停流与 PTZ 归一化"
    },
    @{
        Source      = "onvif-implementation/phase-4-restful-api-and-web-ui.md"
        Target      = "ONVIF-Phase-4-RESTful-API-and-Web-UI.md"
        Title       = "Phase 4: RESTful API 与 Web UI"
        Category    = "ONVIF 协议工程"
        Description = "控制器端点设计、Axios API 封装、Vue 2 设备主台账、自动探测抽屉与云台控制盘"
    },
    @{
        Source      = "onvif-implementation/phase-5-testing-verification-and-doc-sync.md"
        Target      = "ONVIF-Phase-5-Testing-Verification-and-Doc-Sync.md"
        Title       = "Phase 5: 联调验证与交付基线"
        Category    = "ONVIF 协议工程"
        Description = "JUnit 5 单元测试、海康/大华/宇视/雄迈多品牌 IPC 实测兼容性矩阵与验收 Checklist"
    },
    @{
        Source      = "debug/2026-09-09-onvif-debug-optimization-plan.md"
        Target      = "ONVIF-Debug-Optimization-Plan.md"
        Title       = "ONVIF 接入调试与优化方案 (修订版)"
        Category    = "调试与运维"
        Description = "时钟偏斜导致鉴权失败、WS-Discovery 端口占用、组播丢包及 Profile 解析空指针专项排查"
    }
)

# 3. 链接转换辅助函数
function Convert-WikiLinks {
    param([string]$Content)

    # 3.1 移除首行文件路径注释 (如 <!-- doc/reaticle_docs/... -->)
    $Content = [regex]::Replace($Content, '^\s*<!--\s*doc/reaticle_docs/.*?-->\s*\r?\n', '')

    # 3.2 替换指向 doc/reaticle_docs 下文档的链接为 Wiki Page 链接
    $LinkReplacementRules = @(
        @{ Pattern = '(?i)(?:doc/reaticle_docs/)?compile\.md(?:#[^)]*)?'; Replacement = 'Compile-and-Dev-Guide' },
        @{ Pattern = '(?i)(?:doc/reaticle_docs/)?docker-all-in-one-solution\.md(?:#[^)]*)?'; Replacement = 'Docker-All-in-One-Solution' },
        @{ Pattern = '(?i)(?:doc/reaticle_docs/)?docker-all-in-one-implementation\.md(?:#[^)]*)?'; Replacement = 'Docker-All-in-One-Implementation' },
        @{ Pattern = '(?i)(?:doc/reaticle_docs/)?onvif-support-solution\.md(?:#[^)]*)?'; Replacement = 'ONVIF-Support-Solution' },
        @{ Pattern = '(?i)(?:doc/reaticle_docs/)?onvif-implementation/README\.md(?:#[^)]*)?'; Replacement = 'ONVIF-Implementation-Guide' },
        @{ Pattern = '(?i)(?:doc/reaticle_docs/)?(?:onvif-implementation/)?phase-1-soap-engine-and-discovery\.md(?:#[^)]*)?'; Replacement = 'ONVIF-Phase-1-SOAP-Engine-and-Discovery' },
        @{ Pattern = '(?i)(?:doc/reaticle_docs/)?(?:onvif-implementation/)?phase-2-persistence-and-channel-sync\.md(?:#[^)]*)?'; Replacement = 'ONVIF-Phase-2-Persistence-and-Channel-Sync' },
        @{ Pattern = '(?i)(?:doc/reaticle_docs/)?(?:onvif-implementation/)?phase-3-media-proxy-and-ptz-control\.md(?:#[^)]*)?'; Replacement = 'ONVIF-Phase-3-Media-Proxy-and-PTZ-Control' },
        @{ Pattern = '(?i)(?:doc/reaticle_docs/)?(?:onvif-implementation/)?phase-4-restful-api-and-web-ui\.md(?:#[^)]*)?'; Replacement = 'ONVIF-Phase-4-RESTful-API-and-Web-UI' },
        @{ Pattern = '(?i)(?:doc/reaticle_docs/)?(?:onvif-implementation/)?phase-5-testing-verification-and-doc-sync\.md(?:#[^)]*)?'; Replacement = 'ONVIF-Phase-5-Testing-Verification-and-Doc-Sync' },
        @{ Pattern = '(?i)(?:doc/reaticle_docs/)?(?:debug/)?2026-09-09-onvif-debug-optimization-plan\.md(?:#[^)]*)?'; Replacement = 'ONVIF-Debug-Optimization-Plan' }
    )

    foreach ($rule in $LinkReplacementRules) {
        $regexPattern = '\]\(' + $rule.Pattern + '\)'
        $replacement = '](' + $rule.Replacement + ')'
        $Content = [regex]::Replace($Content, $regexPattern, $replacement)

        # 处理带 file:/// 协议的绝对路径形式
        $fileRegexPattern = '\]\(file:///[a-zA-Z]:/[^)]*?' + $rule.Pattern + '\)'
        $Content = [regex]::Replace($Content, $fileRegexPattern, $replacement)
    }

    # 3.3 替换指向代码仓其他源文件路径为标准的 GitHub Blob 链接
    $RepoBaseUrl = "https://github.com/TREYWANGCQU/wvp-GB28181-pro/blob/master"

    # 处理 file:///d:/offices/Github/wvp-GB28181-pro/src/...
    $Content = [regex]::Replace($Content, '\]\(file:///[a-zA-Z]:/offices/Github/wvp-GB28181-pro/([^)]+)\)', "]($RepoBaseUrl/`$1)")

    # 处理相对代码仓路径链接：如 [pom.xml](../../pom.xml#L63) 或 [src/...](../../../src/...)
    $Content = [regex]::Replace($Content, '\]\(\.\./\.\./(?:(?:\.\./)?)*([^)]+)\)', {
            param($m)
            $target = $m.Groups[1].Value
            # 排除已是 Wiki 页面的情况
            if ($target -match '^(?:Compile-|Docker-|ONVIF-)') {
                return "]($target)"
            }
            # URL 编码中文路径（如 "数据库"）
            $segments = $target -split '/'
            $encodedSegments = $segments | ForEach-Object { [System.Uri]::EscapeDataString($_) }
            $encodedTarget = $encodedSegments -join '/'
            return "]($RepoBaseUrl/$encodedTarget)"
        })

    return $Content
}

# 4. 执行文档转换与输出
Write-Host "[Wiki] 正在转换并输出各核心文档页面..." -ForegroundColor Green

foreach ($mapping in $DocMappings) {
    $srcFile = Join-Path $DocsRoot $mapping.Source
    $destFile = Join-Path $WikiDir $mapping.Target

    if (-not (Test-Path $srcFile)) {
        Write-Warning "源文件未找到，跳过: $srcFile"
        continue
    }

    Write-Host "  -> 写入: $($mapping.Target)" -ForegroundColor Cyan
    $rawText = Get-Content $srcFile -Raw -Encoding UTF8
    $transformed = Convert-WikiLinks -Content $rawText

    # 确保保存为不带 BOM 的标准 UTF-8
    [System.IO.File]::WriteAllText($destFile, $transformed, [System.Text.UTF8Encoding]::new($false))
}

# 5. 生成全局 _Sidebar.md
$SidebarPath = Join-Path $WikiDir "_Sidebar.md"
Write-Host "[Wiki] 正在生成全局侧边栏 _Sidebar.md..." -ForegroundColor Green
$SidebarContent = @"
<!-- _Sidebar.md -->

### [🏠 首页 (Home)](Home)

---

### 🛠️ 编译与本地开发
- [编译与开发全指南](Compile-and-Dev-Guide)

---

### 🐳 Docker All-in-One 全栈交付
- [架构方案与设计规范](Docker-All-in-One-Solution)
- [双机协同编译与发布实施细则](Docker-All-in-One-Implementation)

---

### 📹 ONVIF 协议工程实现
- [ONVIF 协议实施总方案](ONVIF-Support-Solution)
- [五阶段实施工程指南总览](ONVIF-Implementation-Guide)
- [阶段一：通信底座与网络探测](ONVIF-Phase-1-SOAP-Engine-and-Discovery)
- [阶段二：持久化与通道同步](ONVIF-Phase-2-Persistence-and-Channel-Sync)
- [阶段三：流媒体调度与云台控制](ONVIF-Phase-3-Media-Proxy-and-PTZ-Control)
- [阶段四：RESTful API 与 Web UI](ONVIF-Phase-4-RESTful-API-and-Web-UI)
- [阶段五：联调验证与交付基线](ONVIF-Phase-5-Testing-Verification-and-Doc-Sync)

---

### 🩺 调试与问题排查
- [ONVIF 接入调试与优化方案](ONVIF-Debug-Optimization-Plan)

---

### 🔗 快捷资源
- [GitHub 源码仓库](https://github.com/TREYWANGCQU/wvp-GB28181-pro)
- [Docker Hub 镜像中心](https://hub.docker.com/r/reaticle/wvp-pro-aio)
- [提交 Issue 反馈](https://github.com/TREYWANGCQU/wvp-GB28181-pro/issues)
"@

[System.IO.File]::WriteAllText($SidebarPath, $SidebarContent.Trim(), [System.Text.UTF8Encoding]::new($false))

# 6. 生成全局 _Footer.md
$FooterPath = Join-Path $WikiDir "_Footer.md"
Write-Host "[Wiki] 正在生成全局页脚 _Footer.md..." -ForegroundColor Green
$FooterContent = @"
<!-- _Footer.md -->

---

<div align="center">

**WVP-PRO (GB28181-2016/2022 & ONVIF 双协议支持开源分支)**

维护团队：Reaticle & WVP-PRO 贡献者 · 源码仓：[TREYWANGCQU/wvp-GB28181-pro](https://github.com/TREYWANGCQU/wvp-GB28181-pro) · 镜像：[reaticle/wvp-pro-aio](https://hub.docker.com/r/reaticle/wvp-pro-aio)

</div>
"@

[System.IO.File]::WriteAllText($FooterPath, $FooterContent.Trim(), [System.Text.UTF8Encoding]::new($false))

# 7. 生成门户主页 Home.md
$HomePath = Join-Path $WikiDir "Home.md"
Write-Host "[Wiki] 正在生成门户主页 Home.md..." -ForegroundColor Green
$HomeContent = @"
# WVP-PRO (GB28181 & ONVIF 双协议分支) 技术全景文档

欢迎查阅 **WVP-PRO** 深度扩展分支的技术全景 Wiki。本项目在原生支持 **GB28181-2016 / 2022** 国标视频协议的基础上，**自研了高性能原生 ONVIF 协议支持**（WS-Discovery 局域网探测、时钟偏斜动态补偿 WS-Security、Profile 码流解析、ZLM 自动拉流接管与 PTZ 云台坐标归一化控制），并实现了 **Docker All-in-One 多架构一体化镜像极速交付（约300MB）**。

---

## 🌟 核心特性与架构升级

```mermaid
flowchart TD
    subgraph IPC["安防摄像机与监控设备群"]
        GB_CAM["GB28181 国标摄像头<br/>(海康 / 大华 / 宇视等)"]
        ONVIF_CAM["ONVIF 摄像头<br/>(Profile S / T / 全网探测)"]
    end

    subgraph WVP_STACK["WVP-PRO 统一信令与管理调度底座"]
        direction TB
        SIP_ENGINE["GB28181 SIP 信令栈<br/>(JAIN-SIP / 状态维护 / 目录同步)"]
        ONVIF_ENGINE["自研轻量 SOAP 引擎<br/>(WS-Discovery / WS-Security / PTZ 归一化)"]
        BIZ_CORE["核心调度与数据中心<br/>(Spring Boot 3.4 / JDK 21 虚拟线程)"]
        WEB_UI["现代响应式 Web UI 控制台<br/>(Vue 2 / 分屏预览 / 操控盘)"]
    end

    subgraph MEDIA["高性能流媒体分发中枢"]
        ZLM["ZLMediaKit (C++20 高性能引擎)<br/>- RTP 国标收流池<br/>- ONVIF RTSP 代理接管<br/>- WebRTC / FLV / HLS / 对讲分发"]
    end

    subgraph STORAGE["数据与缓存基础设施"]
        DB[(MariaDB / MySQL 8.0)]
        CACHE[(Redis 7.0 缓存)]
    end

    GB_CAM <-->|SIP 信令 :8116| SIP_ENGINE
    GB_CAM -->|RTP 媒体流 :30000-30050| ZLM

    ONVIF_CAM <-->|UDP 探测 :3702 / HTTP SOAP :80| ONVIF_ENGINE
    ONVIF_CAM -->|RTSP 媒体流 :554| ZLM

    SIP_ENGINE <--> BIZ_CORE
    ONVIF_ENGINE <--> BIZ_CORE
    BIZ_CORE <-->|RESTful & Webhook| ZLM
    BIZ_CORE <--> DB
    BIZ_CORE <--> CACHE
    WEB_UI <-->|HTTP :18080| BIZ_CORE
```

1. **双协议全功能融合**：打破传统国标平台的接入限制，局域网内的海康、大华、宇视、雄迈等 ONVIF 摄像头通过一键探测即可秒级入网，统一汇入设备通道树并赋予同等的点播、录像与 PTZ 控制能力；
2. **Zero-New-Dependency 依赖纯洁性**：坚持采用 JDK 21 原生 HttpClient 与现有 dom4j 宽容解析实现 SOAP 引擎，零引入 CXF、Axis 等笨重第三方依赖，完美契合 Java 21 虚拟线程高并发特性；
3. **Docker All-in-One 极速全栈交付**：首创「WVP-PRO + ZLMediaKit + MariaDB + Redis」四合一生产级单容器运行体系，支持 `linux/amd64` 与 `linux/arm64`（树莓派/Apple Silicon）双架构，原生集成自愈 Entrypoint 防御，开箱即用。

---

## 📚 知识库全景导航

### 🛠️ 1. 编译与本地开发指南
*适合需要二次开发、修改源码或搭建断点调试环境的开发工程师。*
- **[编译与开发全指南](Compile-and-Dev-Guide)**：
  - 采用 **“Windows 11 开发机 + Linux 离线测试机 + macOS 构建机”** 的三机高效分工拓扑；
  - 彻底规避网络代理握手截断与双向 Webhook 穿透阻隔；
  - 涵盖前端依赖安装与生产构建归位、后端 Maven 打包及离线环境一键拉起。

---

### 🐳 2. Docker All-in-One 全栈交付
*适合需要快速部署、云原生集成或实施交付的运维与架构工程师。*
- **[All-in-One 镜像合并打包架构方案](Docker-All-in-One-Solution)**：
  - 系统架构分析、端口暴露矩阵、硬约束与边界契约；
  - Alpine 3.19 极简运行底座与多阶段构建瘦身设计（最终压缩镜像 $\le$ 280MB）。
- **[All-in-One 双机协同编译与 Docker Hub 发布实施细则](Docker-All-in-One-Implementation)**：
  - Windows 主机与 macOS/Colima 协同构建多架构镜像；
  - 具备信号平滑退出与配置热覆盖自愈机制的 `entrypoint.sh` 守护引擎；
  - Docker Hub 镜像中心（`reaticle/wvp-pro-aio`）拉取与一键启动使用手册。

---

### 📹 3. ONVIF 原生协议扩展全体系
*详细阐述从协议底座、数据建模、流媒体调度到前端交互与多品牌兼容实测的完整落地过程。*
- **[ONVIF 协议支持技术实施方案](ONVIF-Support-Solution)**：
  - 为什么自研轻量 SOAP 引擎？架构契约矩阵与代码组织规划。
- **[五阶段实施工程指南总览](ONVIF-Implementation-Guide)**：
  - 五阶段工程分解全景与跨阶段演进路线图。
- **[阶段一：通信底座与网络探测](ONVIF-Phase-1-SOAP-Engine-and-Discovery)**：
  - JDK 21 原生 HttpClient 通信栈、动态时钟偏斜补偿与 WS-Discovery 探测器。
- **[阶段二：持久化与通道同步](ONVIF-Phase-2-Persistence-and-Channel-Sync)**：
  - 增量 SQL 表结构设计、OnvifDevice 领域模型与通道归一化挂接 (`data_type=4`)。
- **[阶段三：流媒体调度与云台控制](ONVIF-Phase-3-Media-Proxy-and-PTZ-Control)**：
  - ZLM StreamProxy 代理拉流接管、无人观看自动停流与 PTZ 坐标归一化模型。
- **[阶段四：RESTful API 与 Web UI](ONVIF-Phase-4-RESTful-API-and-Web-UI)**：
  - 控制器端点设计、前端 Axios API 封装、Vue 2 设备台账、搜寻抽屉与控制盘。
- **[阶段五：联调验证与交付基线](ONVIF-Phase-5-Testing-Verification-and-Doc-Sync)**：
  - JUnit 5 自动化单元测试、海康/大华/宇视/雄迈主流 IPC 兼容性实测与避坑指南。

---

### 🩺 4. 调试与故障排查
*实战踩坑记录与快速定位手册。*
- **[ONVIF 接入调试与优化方案 (修订版)](ONVIF-Debug-Optimization-Plan)**：
  - 深度解析时钟偏斜鉴权失败、WS-Discovery 端口抢占与组播丢包根因，提供经过验证的高可靠解决方案。

---

## ⚡ 极速起步 (Quick Start)

### 选项 A：使用 Docker 极速体验 (推荐)
仅需一条命令即可拉起包含流媒体与数据库的全套生产环境：
```bash
docker run -d \
  --name wvp-aio \
  --restart unless-stopped \
  --net=host \
  -v /opt/wvp-aio-data/data/mysql:/var/lib/mysql \
  -v /opt/wvp-aio-data/data/record:/opt/media/bin/www/record \
  -v /opt/wvp-aio-data/logs:/opt/wvp/logs \
  reaticle/wvp-pro-aio:2.7.4
```
访问 `http://<宿主机IP>:18080`，使用默认账号密码 `admin` / `admin` 登录。

### 选项 B：源码开发启动
1. 查阅 [编译与开发指南](Compile-and-Dev-Guide) 准备基础依赖；
2. 启动测试机 MySQL/Redis/ZLM 基础设施；
3. 执行前端编译并将静态资源写入后端；
4. 在 IDE 中启动 `com.genersoft.iot.vmp.VManageBootstrap` 即可开启断点调试。

---

## 🤝 参与维护与反馈

- **GitHub Issues**：[提交 Bug 报告或功能建议](https://github.com/TREYWANGCQU/wvp-GB28181-pro/issues)
- **分支维护人**：`y.wang@reaticle.com`
- **原版开源项目**：感谢 648540858 、夏楚等前辈对于 WVP-PRO 核心架构做出的卓越贡献！
"@

[System.IO.File]::WriteAllText($HomePath, $HomeContent.Trim(), [System.Text.UTF8Encoding]::new($false))

# 8. Git 版本控制与提交检查
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "[Wiki] 检查 Wiki 本地仓库 Git 变更状态..." -ForegroundColor Cyan

$CurrentLocation = Get-Location
try {
    Set-Location $WikiDir

    git status -s

    if ($Push) {
        Write-Host "[Wiki] 正在暂存所有变更并提交..." -ForegroundColor Yellow
        git add .
        git commit -m $CommitMessage
        if ($LASTEXITCODE -eq 0) {
            Write-Host "[Wiki] 正在推送到远端 Wiki master 分支..." -ForegroundColor Yellow
            git push origin master
            if ($LASTEXITCODE -eq 0) {
                Write-Host "[Wiki] SUCCESS: Wiki 第一次同步提交已成功推送至 GitHub!" -ForegroundColor Green
            }
            else {
                Write-Error "Wiki 推送失败，请检查网络或 SSH 权限。"
            }
        }
        else {
            Write-Host "[Wiki] 没有检测到需要提交的变更。" -ForegroundColor Gray
        }
    }
    else {
        Write-Host "[Wiki] 生成完成。若需提交推送，请添加 -Push 参数重新执行，或进入 $WikiDir 手动执行 git push。" -ForegroundColor Green
    }
}
finally {
    Set-Location $CurrentLocation
}
