# scripts/bump-version.ps1
<#
.SYNOPSIS
    WVP-PRO 全局版本号联动提升与 Docker 发布同步工具
.DESCRIPTION
    依据单一权威信任源原则，一次性原子更新 pom.xml、docker/aio/build.sh、
    docker/docker-compose.aio.yml 与 docker/push.sh，杜绝多处硬编码导致的“版本漂移”。
.PARAMETER NewVersion
    目标版本号，必须符合语义化版本规范，例如 2.8.0 或 2.8.0-rc1
.EXAMPLE
    pwsh ./scripts/bump-version.ps1 -NewVersion "2.8.0"
#>

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [ValidatePattern('^\d+\.\d+\.\d+(-[a-zA-Z0-9.]+)?$')]
    [string]$NewVersion
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = (Resolve-Path "$ScriptDir/..").Path

Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "  WVP-PRO 版本号联动提升与 Docker 发布同步工具" -ForegroundColor Cyan
Write-Host "  目标新版本: $NewVersion" -ForegroundColor Green
Write-Host "  工程根目录: $ProjectRoot" -ForegroundColor Gray
Write-Host "============================================================" -ForegroundColor Cyan

# 1. 更新 pom.xml
$PomPath = Join-Path $ProjectRoot "pom.xml"
if (Test-Path $PomPath) {
    Write-Host "[1/4] 正在更新 pom.xml ..." -ForegroundColor Yellow
    $pomContent = [System.IO.File]::ReadAllText($PomPath, [System.Text.Encoding]::UTF8)
    
    # 仅精准替换 wvp-pro artifact 下方的 version 声明，避免误伤 parent version
    $pomRegex = '(<artifactId>wvp-pro</artifactId>\s*<version>)([^<]+)(</version>)'
    if ($pomContent -match $pomRegex) {
        $oldVersion = $Matches[2]
        $pomContent = [System.Text.RegularExpressions.Regex]::Replace($pomContent, $pomRegex, "`$1$NewVersion`$3")
        [System.IO.File]::WriteAllText($PomPath, $pomContent, [System.Text.Encoding]::UTF8)
        Write-Host "  -> pom.xml 更新成功: $oldVersion => $NewVersion" -ForegroundColor Green
    } else {
        Write-Warning "  -> 未能在 pom.xml 中找到 wvp-pro 的 version 标签，请手工核查！"
    }
} else {
    Write-Warning "  -> 未找到 pom.xml: $PomPath"
}

# 2. 更新 docker/aio/build.sh
$AioBuildPath = Join-Path $ProjectRoot "docker/aio/build.sh"
if (Test-Path $AioBuildPath) {
    Write-Host "[2/4] 正在更新 docker/aio/build.sh ..." -ForegroundColor Yellow
    $aioContent = [System.IO.File]::ReadAllText($AioBuildPath, [System.Text.Encoding]::UTF8)
    $aioRegex = 'VERSION="\$\{VERSION:-[^}]+\}"'
    if ($aioContent -match $aioRegex) {
        $aioContent = [System.Text.RegularExpressions.Regex]::Replace($aioContent, $aioRegex, "VERSION=`"`${VERSION:-$NewVersion}`"")
        [System.IO.File]::WriteAllText($AioBuildPath, $aioContent, [System.Text.Encoding]::UTF8)
        Write-Host "  -> docker/aio/build.sh 默认版本更新为: $NewVersion" -ForegroundColor Green
    } else {
        Write-Warning "  -> 未能匹配到 docker/aio/build.sh 中的 VERSION 变量，请核对格式"
    }
}

# 3. 更新 docker/docker-compose.aio.yml
$ComposeAioPath = Join-Path $ProjectRoot "docker/docker-compose.aio.yml"
if (Test-Path $ComposeAioPath) {
    Write-Host "[3/4] 正在更新 docker/docker-compose.aio.yml ..." -ForegroundColor Yellow
    $composeContent = [System.IO.File]::ReadAllText($ComposeAioPath, [System.Text.Encoding]::UTF8)
    $composeRegex = 'image:\s+reaticle/wvp-pro-aio:[^\s\r\n]+'
    if ($composeContent -match $composeRegex) {
        $composeContent = [System.Text.RegularExpressions.Regex]::Replace($composeContent, $composeRegex, "image: reaticle/wvp-pro-aio:$NewVersion")
        [System.IO.File]::WriteAllText($ComposeAioPath, $composeContent, [System.Text.Encoding]::UTF8)
        Write-Host "  -> docker-compose.aio.yml 镜像标签更新为: reaticle/wvp-pro-aio:$NewVersion" -ForegroundColor Green
    } else {
        Write-Warning "  -> 未能匹配到 docker-compose.aio.yml 中的 image 声明"
    }
}

# 4. 更新 docker/push.sh
$PushPath = Join-Path $ProjectRoot "docker/push.sh"
if (Test-Path $PushPath) {
    Write-Host "[4/4] 正在更新 docker/push.sh ..." -ForegroundColor Yellow
    $pushContent = [System.IO.File]::ReadAllText($PushPath, [System.Text.Encoding]::UTF8)
    $pushRegex = '(?m)^version=[^\r\n]+'
    if ($pushContent -match $pushRegex) {
        $pushContent = [System.Text.RegularExpressions.Regex]::Replace($pushContent, $pushRegex, "version=$NewVersion")
        [System.IO.File]::WriteAllText($PushPath, $pushContent, [System.Text.Encoding]::UTF8)
        Write-Host "  -> docker/push.sh version 变量更新为: $NewVersion" -ForegroundColor Green
    }
}

Write-Host "`n============================================================" -ForegroundColor Cyan
Write-Host "  版本联动同步完毕！推荐后续发版操作命令：" -ForegroundColor Cyan
Write-Host "  1. 编译打包: mvn clean package -DskipTests" -ForegroundColor White
Write-Host "  2. 构建发布: ./docker/aio/build.sh push" -ForegroundColor White
Write-Host "  3. Git 打标: git commit -am `"chore: bump version to $NewVersion`"" -ForegroundColor White
Write-Host "               git tag v$NewVersion" -ForegroundColor White
Write-Host "               git push origin master --tags" -ForegroundColor White
Write-Host "  4. 生成说明: /release-curator $NewVersion" -ForegroundColor White
Write-Host "============================================================" -ForegroundColor Cyan
