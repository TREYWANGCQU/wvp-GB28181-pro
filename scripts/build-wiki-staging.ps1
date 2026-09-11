# scripts/build-wiki-staging.ps1
<#
.SYNOPSIS
    WVP-PRO GitHub Wiki 本地编译与受控暂存构建引擎
.DESCRIPTION
    1. 自动递归扫描 doc/reaticle_docs/ 目录下的所有工程文档（包含 feats/、research/、debug/、onvif-implementation/ 等任意深度的子目录）；
    2. 依据命名空间前缀规则，将多级子目录文档映射为 GitHub Wiki 扁平化页面规范；
    3. 自动转换相对文件引用、锚点与代码仓源码超链接（映射至 GitHub master 分支）；
    4. 自动提取文档标题与元数据，动态渲染全局导航大纲与门户首页（Home.md, _Sidebar.md, _Footer.md）；
    5. 将所有生成物统一写入代码仓受控暂存区 doc/wiki_staging/（以供本地 Git 审查或云端 CI 同步）。
.PARAMETER OutputDir
    Wiki 暂存输出目录，默认为 doc/wiki_staging
.PARAMETER SyncToWikiDir
    可选：若指定，则同步复制至本地克隆的 .wiki 仓库目录
.PARAMETER Push
    可选：若指定且存在 SyncToWikiDir，则在构建完成后执行 git commit 与 git push
.PARAMETER CommitMessage
    可选：提交信息，默认为 'docs(wiki): automated sync from doc/wiki_staging'
#>

[CmdletBinding()]
param(
    [string]$OutputDir = "",
    [string]$SyncToWikiDir = "",
    [switch]$Push,
    [string]$CommitMessage = "docs(wiki): automated sync from doc/wiki_staging"
)

$ErrorActionPreference = "Stop"

# 1. 路径初始化
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$DocsRoot = Join-Path $ProjectRoot "doc\reaticle_docs"

if (-not (Test-Path $DocsRoot)) {
    Write-Error "文档源目录不存在: $DocsRoot"
}

if ([string]::IsNullOrWhiteSpace($OutputDir)) {
    $OutputDir = Join-Path $ProjectRoot "doc\wiki_staging"
}

if (-not (Test-Path $OutputDir)) {
    New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null
}

Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "[Wiki Build] WVP-PRO Wiki 受控暂存构建引擎启动..." -ForegroundColor Cyan
Write-Host "[Wiki Build] 文档源目录: $DocsRoot" -ForegroundColor Gray
Write-Host "[Wiki Build] 暂存输出区: $OutputDir" -ForegroundColor Gray
Write-Host "============================================================" -ForegroundColor Cyan

# 2. 静态已知别名映射表 (保障向后兼容历史 Wiki Page 名称)
$KnownTargetAliases = @{
    "compile.md"                                                      = "Compile-and-Dev-Guide.md"
    "docker-all-in-one-solution.md"                                   = "Docker-All-in-One-Solution.md"
    "docker-all-in-one-implementation.md"                             = "Docker-All-in-One-Implementation.md"
    "onvif-support-solution.md"                                       = "ONVIF-Support-Solution.md"
    "onvif-implementation/README.md"                                  = "ONVIF-Implementation-Guide.md"
    "onvif-implementation/phase-1-soap-engine-and-discovery.md"       = "ONVIF-Phase-1-SOAP-Engine-and-Discovery.md"
    "onvif-implementation/phase-2-persistence-and-channel-sync.md"    = "ONVIF-Phase-2-Persistence-and-Channel-Sync.md"
    "onvif-implementation/phase-3-media-proxy-and-ptz-control.md"     = "ONVIF-Phase-3-Media-Proxy-and-PTZ-Control.md"
    "onvif-implementation/phase-4-restful-api-and-web-ui.md"          = "ONVIF-Phase-4-RESTful-API-and-Web-UI.md"
    "onvif-implementation/phase-5-testing-verification-and-doc-sync.md" = "ONVIF-Phase-5-Testing-Verification-and-Doc-Sync.md"
    "debug/2026-09-09-onvif-debug-optimization-plan.md"              = "ONVIF-Debug-Optimization-Plan.md"
    "wiki-automation-solution.md"                                     = "Wiki-Automation-Solution.md"
}

# 辅助函数：将任意源相对路径转为标准的 Wiki 扁平文件名
function Get-WikiPageName {
    param([string]$RelativeSourcePath)

    $normalized = $RelativeSourcePath.Replace("\", "/")
    if ($KnownTargetAliases.ContainsKey($normalized)) {
        return $KnownTargetAliases[$normalized]
    }

    # 针对通用文件：根据子目录前缀与文件名组合大写连字符命名
    $parts = $normalized.Split('/')
    $capitalizedParts = foreach ($part in $parts) {
        $base = [System.IO.Path]::GetFileNameWithoutExtension($part)
        # 将连字符分隔的单词首字母大写
        $words = $base -split '[-_]'
        $capWords = foreach ($w in $words) {
            if ($w.Length -gt 0) {
                $w.Substring(0, 1).ToUpper() + $w.Substring(1)
            }
        }
        $capWords -join '-'
    }

    return ($capitalizedParts -join '-') + ".md"
}

# 3. 递归扫描 doc/reaticle_docs 获取全部 Markdown 文件
$SourceFiles = Get-ChildItem -Path $DocsRoot -Recurse -Filter *.md | Where-Object {
    $_.FullName -notmatch '[\\/]doc[\\/]wiki_staging[\\/]'
}

$DocItems = [System.Collections.Generic.List[PSCustomObject]]::new()

foreach ($file in $SourceFiles) {
    $relative = (Resolve-Path -Path $file.FullName -Relative).Replace(".\doc\reaticle_docs\", "").Replace("./doc/reaticle_docs/", "").Replace("\", "/")
    $targetName = Get-WikiPageName -RelativeSourcePath $relative
    $pageBase = [System.IO.Path]::GetFileNameWithoutExtension($targetName)

    # 读取首行获取标题 (跳过可能存在的首行路径注释，如 <!-- doc/... --> 或 # doc/...)
    $rawContent = Get-Content -Path $file.FullName -Raw -Encoding UTF8
    $title = ""
    $lines = $rawContent -split "`r?`n"
    foreach ($line in $lines) {
        if ($line -match '^\s*<!--\s*doc/reaticle_docs/.*?-->') { continue }
        if ($line -match '^\s*#\s+doc/reaticle_docs/') { continue }
        if ($line -match '^\s*#\s+(.+)$') {
            $title = $matches[1].Trim()
            break
        }
    }
    if ([string]::IsNullOrWhiteSpace($title)) {
        $title = $pageBase
    }

    # 判断所属分类
    $category = "核心架构与指南"
    if ($relative -match '^onvif-implementation/' -or $relative -eq 'onvif-support-solution.md') {
        $category = "ONVIF 协议工程"
    }
    elseif ($relative -match '^docker-') {
        $category = "Docker 全栈交付"
    }
    elseif ($relative -match '^feats/') {
        $category = "国标特性与业务扩展"
    }
    elseif ($relative -match '^research/') {
        $category = "前沿探索与源码剖析"
    }
    elseif ($relative -match '^debug/') {
        $category = "调试与故障排查"
    }
    elseif ($relative -eq 'compile.md') {
        $category = "开发与编译"
    }
    elseif ($relative -eq 'wiki-automation-solution.md') {
        $category = "工程架构与工具链"
    }

    $DocItems.Add([PSCustomObject]@{
        RelativeSource = $relative
        SourceFullPath = $file.FullName
        TargetFileName = $targetName
        PageBase       = $pageBase
        Title          = $title
        Category       = $category
        RawContent     = $rawContent
    })
}

Write-Host "[Wiki Build] 发现源文档共 $($DocItems.Count) 篇" -ForegroundColor Green

# 4. 构建全量链接映射规则
$RepoBaseUrl = "https://github.com/TREYWANGCQU/wvp-GB28181-pro/blob/master"

function Convert-WikiContent {
    param(
        [string]$Content,
        [System.Collections.Generic.List[PSCustomObject]]$AllDocs
    )

    # 4.1 移除首行文件路径注释 (HTML 格式与 Markdown 格式)
    $Content = [regex]::Replace($Content, '^\s*<!--\s*doc/reaticle_docs/.*?-->\s*\r?\n', '')
    $Content = [regex]::Replace($Content, '^\s*#\s+doc/reaticle_docs/.*?\r?\n', '')

    # 4.2 替换文档间内联引用为 Wiki Page 扁平链接
    foreach ($doc in $AllDocs) {
        $escapedSource = [regex]::Escape($doc.RelativeSource)
        $escapedBase = [regex]::Escape([System.IO.Path]::GetFileName($doc.RelativeSource))
        $page = $doc.PageBase

        # 匹配各种形式的内部引用：
        # - (doc/reaticle_docs/xxx.md)
        # - (../xxx.md)
        # - (xxx.md)
        $patterns = @(
            "(?i)(?:doc/reaticle_docs/)?$escapedSource(?:#[^)]*)?",
            "(?i)(?:\.\./)+$escapedSource(?:#[^)]*)?",
            "(?i)(?:\.\./)+(?:feats|research|debug|onvif-implementation)/$escapedBase(?:#[^)]*)?",
            "(?i)\b$escapedBase(?:#[^)]*)?"
        )

        foreach ($pat in $patterns) {
            $regexPattern = '\]\(' + $pat + '\)'
            $Content = [regex]::Replace($Content, $regexPattern, "]($page)")

            # 处理 file:/// 形式
            $filePattern = '\]\(file:///[a-zA-Z]:/[^)]*?' + $pat + '\)'
            $Content = [regex]::Replace($Content, $filePattern, "]($page)")
        }
    }

    # 4.3 处理项目源码链接（指向工程根目录其他文件或代码路径）
    $Content = [regex]::Replace($Content, '\]\(file:///[a-zA-Z]:/offices/Github/wvp-GB28181-pro/([^)]+)\)', "]($RepoBaseUrl/`$1)")

    # 匹配类似 [pom.xml](../../pom.xml#L63) 或 [src/...](../../../src/...)
    $Content = [regex]::Replace($Content, '\]\(\.\./\.\./(?:(?:\.\./)?)*([^)]+)\)', {
        param($m)
        $target = $m.Groups[1].Value
        # 排除已经是 Wiki 页面的情况
        if ($target -match '^[A-Z][a-zA-Z0-9\-]+$' -or $target -match '^(?:Compile-|Docker-|ONVIF-|Feats-|Research-|Debug-|Wiki-)') {
            return "]($target)"
        }
        $segments = $target -split '/'
        $encodedSegments = $segments | ForEach-Object { [System.Uri]::EscapeDataString($_) }
        $encodedTarget = $encodedSegments -join '/'
        return "]($RepoBaseUrl/$encodedTarget)"
    })

    return $Content
}

# 5. 执行文档转换与输出至 Staging 目录
Write-Host "[Wiki Build] 正在转换并输出受控暂存文件..." -ForegroundColor Green

foreach ($doc in $DocItems) {
    $destFile = Join-Path $OutputDir $doc.TargetFileName
    Write-Host "  -> [Staging] $($doc.TargetFileName)" -ForegroundColor Cyan
    $transformed = Convert-WikiContent -Content $doc.RawContent -AllDocs $DocItems

    [System.IO.File]::WriteAllText($destFile, $transformed, [System.Text.UTF8Encoding]::new($false))
}

# 6. 动态生成 _Sidebar.md
$SidebarPath = Join-Path $OutputDir "_Sidebar.md"
Write-Host "[Wiki Build] 正在生成全局侧边栏 _Sidebar.md..." -ForegroundColor Green

$sb = [System.Text.StringBuilder]::new()
[void]$sb.AppendLine("<!-- _Sidebar.md -->")
[void]$sb.AppendLine()
[void]$sb.AppendLine("### [🏠 首页 (Home)](Home)")
[void]$sb.AppendLine()
[void]$sb.AppendLine("---")

$categories = @(
    "开发与编译",
    "Docker 全栈交付",
    "ONVIF 协议工程",
    "国标特性与业务扩展",
    "前沿探索与源码剖析",
    "调试与故障排查",
    "工程架构与工具链"
)

foreach ($cat in $categories) {
    $itemsInCat = $DocItems | Where-Object { $_.Category -eq $cat }
    if ($itemsInCat.Count -gt 0) {
        $icon = "📌"
        switch ($cat) {
            "开发与编译" { $icon = "🛠️" }
            "Docker 全栈交付" { $icon = "🐳" }
            "ONVIF 协议工程" { $icon = "📹" }
            "国标特性与业务扩展" { $icon = "🚀" }
            "前沿探索与源码剖析" { $icon = "🔬" }
            "调试与故障排查" { $icon = "🩺" }
            "工程架构与工具链" { $icon = "⚙️" }
        }

        [void]$sb.AppendLine("### $icon $cat")
        foreach ($item in $itemsInCat) {
            [void]$sb.AppendLine("- [$($item.Title)]($($item.PageBase))")
        }
        [void]$sb.AppendLine()
        [void]$sb.AppendLine("---")
        [void]$sb.AppendLine()
    }
}

[void]$sb.AppendLine("### 🔗 快捷资源")
[void]$sb.AppendLine("- [GitHub 源码仓库](https://github.com/TREYWANGCQU/wvp-GB28181-pro)")
[void]$sb.AppendLine("- [Docker Hub 镜像中心](https://hub.docker.com/r/reaticle/wvp-pro-aio)")
[void]$sb.AppendLine("- [提交 Issue 反馈](https://github.com/TREYWANGCQU/wvp-GB28181-pro/issues)")

[System.IO.File]::WriteAllText($SidebarPath, $sb.ToString().Trim(), [System.Text.UTF8Encoding]::new($false))

# 7. 生成全局 _Footer.md
$FooterPath = Join-Path $OutputDir "_Footer.md"
Write-Host "[Wiki Build] 正在生成全局页脚 _Footer.md..." -ForegroundColor Green
$FooterContent = @"
<!-- _Footer.md -->

---

<div align="center">

**WVP-PRO (GB28181-2016/2022 & ONVIF 双协议支持开源分支)**

维护团队：Reaticle & WVP-PRO 贡献者 · 源码仓：[TREYWANGCQU/wvp-GB28181-pro](https://github.com/TREYWANGCQU/wvp-GB28181-pro) · 镜像：[reaticle/wvp-pro-aio](https://hub.docker.com/r/reaticle/wvp-pro-aio)

</div>
"@
[System.IO.File]::WriteAllText($FooterPath, $FooterContent.Trim(), [System.Text.UTF8Encoding]::new($false))

# 8. 生成门户主页 Home.md
$HomePath = Join-Path $OutputDir "Home.md"
Write-Host "[Wiki Build] 正在生成门户主页 Home.md..." -ForegroundColor Green

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
3. **Docker All-in-One 极速全栈交付**：首创「WVP-PRO + ZLMediaKit + MariaDB + Redis」四合一生产级单容器运行体系，支持 `linux/amd64` 与 `linux/arm64` 双架构，原生集成自愈 Entrypoint 防御，开箱即用；
4. **自动化工程生态闭环**：通过本地 AI Skill 智能编目与 GitHub Actions 云端 CI，实现技术文档与工程代码变更的秒级持续集成与 Wiki 自动发布。

---

## 📚 知识库全景导航

### 🛠️ 1. 编译与本地开发指南
- **[编译与开发全指南](Compile-and-Dev-Guide)**：三机分工拓扑、Windows 11 开发环境、前端/后端编译及测试机离线部署全指南。

---

### 🐳 2. Docker All-in-One 全栈交付
- **[All-in-One 镜像合并打包架构方案](Docker-All-in-One-Solution)**：四合一生产级极简运行底座与多阶段构建瘦身设计。
- **[All-in-One 双机协同编译与 Docker Hub 发布实施细则](Docker-All-in-One-Implementation)**：多架构编译、自愈 Entrypoint 守护与 Docker Hub 镜像中心拉取手册。

---

### 📹 3. ONVIF 原生协议扩展全体系
- **[ONVIF 协议支持技术实施方案](ONVIF-Support-Solution)**：原生轻量自研 SOAP 引擎与架构契约矩阵。
- **[五阶段实施工程指南总览](ONVIF-Implementation-Guide)**：五阶段实施架构全景、类图结构与交付验收门禁。
- **[阶段一：通信底座与网络探测](ONVIF-Phase-1-SOAP-Engine-and-Discovery)**：原生 HttpClient、时钟偏斜动态补偿与 WS-Discovery 探测器。
- **[阶段二：持久化与通道同步](ONVIF-Phase-2-Persistence-and-Channel-Sync)**：增量 DDL 设计、OnvifDevice 模型与通道归一化挂接。
- **[阶段三：流媒体调度与云台控制](ONVIF-Phase-3-Media-Proxy-and-PTZ-Control)**：ZLM StreamProxy 代理拉流接管与 PTZ 坐标归一化模型。
- **[阶段四：RESTful API 与 Web UI](ONVIF-Phase-4-RESTful-API-and-Web-UI)**：控制器端点设计、Axios API 封装与 Vue 2 设备台账交互。
- **[阶段五：联调验证与交付基线](ONVIF-Phase-5-Testing-Verification-and-Doc-Sync)**：JUnit 5 自动化测试与海康/大华/宇视/雄迈多品牌 IPC 实测矩阵。

---

### 🚀 4. 国标特性与业务扩展
- **[国标级联自定义通道编码批量导入方案](Feats-GB-Cascade-Custom-Channel-Batch-Excel-Solution)**：基于 EasyExcel 的多级目录与通道编码批量解析、重命名映射与下级推送机制。
- **[GB28181 语音对讲与广播升级改造方案](Feats-GB28181-Voice-Talk-and-Broadcast-Upgrade-Solution)**：广播与对讲信令解耦、ZLM 端口动态分配与双向音频流传输优化方案。

---

### 🔬 5. 前沿探索与源码剖析
- **[GB28181 语音对讲与广播故障根因剖析报告](Research-GB28181-Voice-Talk-and-Broadcast-Failure-Analysis)**：从抓包证据、SIP INVITE 流程到音频编码协商的端到端全链路故障复盘。

---

### 🩺 6. 调试与故障排查
- **[ONVIF 接入调试与优化方案 (修订版)](ONVIF-Debug-Optimization-Plan)**：时钟偏斜鉴权失败、WS-Discovery 端口占用与 Profile 空指针排查。
- **[ONVIF 批量导出覆盖与 ID 复用优化方案](Debug-2026-09-11-Onvif-Batch-Export-Overwrite-and-Id-Reuse-Plan)**：设备批量导入时主键冲突自愈与配置回填方案。

---

### ⚙️ 7. 工程架构与工具链
- **[GitHub Wiki 自动化改造与持续发布系统方案](Wiki-Automation-Solution)**：本地 AI Skill 智能编目 + 受控 Staging 暂存 + GitHub CI 自动同步架构设计。

---

## ⚡ 极速起步 (Quick Start)

### 选项 A：使用 Docker 极速体验 (推荐)
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
访问 `http://<宿主机IP>:18080`，默认账号密码：`admin` / `admin`。

### 选项 B：源码开发启动
1. 查阅 [编译与开发指南](Compile-and-Dev-Guide) 准备基础环境；
2. 启动测试机 MySQL/Redis/ZLM 基础设施；
3. 执行前端编译并将静态资源写入后端；
4. 运行 `com.genersoft.iot.vmp.VManageBootstrap` 开启本地开发与调试。

---

## 🤝 参与维护与反馈

- **GitHub Issues**：[提交 Bug 报告或功能建议](https://github.com/TREYWANGCQU/wvp-GB28181-pro/issues)
- **分支维护人**：`y.wang@reaticle.com`
- **致谢**：感谢原版 WVP-PRO 与 ZLMediaKit 开源团队做出的卓越贡献！
"@

[System.IO.File]::WriteAllText($HomePath, $HomeContent.Trim(), [System.Text.UTF8Encoding]::new($false))

# 9. 若指定了 SyncToWikiDir，则执行拷贝与可选 Push
if (-not [string]::IsNullOrWhiteSpace($SyncToWikiDir)) {
    if (-not (Test-Path $SyncToWikiDir)) {
        Write-Host "[Wiki Build] 目标 Wiki 目录不存在，尝试从远端克隆: $SyncToWikiDir" -ForegroundColor Yellow
        git clone "git@github.com:TREYWANGCQU/wvp-GB28181-pro.wiki.git" $SyncToWikiDir
    }

    Write-Host "[Wiki Build] 正在同步暂存文件至目标 Wiki 仓库: $SyncToWikiDir" -ForegroundColor Green
    Copy-Item -Path "$OutputDir\*" -Destination $SyncToWikiDir -Recurse -Force

    if ($Push) {
        $cur = Get-Location
        try {
            Set-Location $SyncToWikiDir
            git add .
            git commit -m $CommitMessage
            if ($LASTEXITCODE -eq 0) {
                git push origin master
                Write-Host "[Wiki Build] SUCCESS: 已推送至 Wiki 仓库!" -ForegroundColor Green
            }
        }
        finally {
            Set-Location $cur
        }
    }
}

Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "[Wiki Build] 构建完成！暂存区已同步至: $OutputDir" -ForegroundColor Green
Write-Host "============================================================" -ForegroundColor Cyan
