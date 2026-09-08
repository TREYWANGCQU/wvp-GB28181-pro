# scripts/sync-to-imac.ps1
<#
.SYNOPSIS
    WVP All-in-One 镜像构建资产极速增量同步脚本 (Windows 11 -> iMac 配合机)
.DESCRIPTION
    1. 检查前端与后端打包产物完整性；
    2. 自动化将 entrypoint.sh、build.sh 换行符转换为 LF；
    3. 通过 SCP / SSH 增量同步资产至 iMac 临时构建目录。
.PARAMETER iMacHost
    配合机 IP 地址，默认为 192.168.1.50
.PARAMETER iMacUser
    配合机 SSH 用户名，默认为 reaticle
.PARAMETER RemoteDir
    配合机远程工作空间，默认为 ~/wvp-aio-build
.PARAMETER Port
    SSH 端口，默认为 22
#>

[CmdletBinding()]
param(
    [string]$iMacHost = "192.168.120.11",
    [string]$iMacUser = "reaticle",
    [string]$RemoteDir = "~/projects/wvp-aio-build",
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
    # 尝试模糊匹配 target/*.jar
    $MatchedJars = Get-ChildItem -Path (Join-Path $ProjectRoot "target") -Filter "wvp-pro-*.jar" -ErrorAction SilentlyContinue
    if ($MatchedJars -and $MatchedJars.Count -gt 0) {
        $JarPath = $MatchedJars[0].FullName
        Write-Host "[Sync] 找到后端构建产物: $JarPath" -ForegroundColor Green
    }
    else {
        Write-Warning "未在 target/ 下找到 wvp-pro-*.jar，若需预置打包同步，请先执行: mvn clean package -DskipTests"
    }
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
}
else {
    $CombinedSql = $BaseSql
}
[System.IO.File]::WriteAllText($CombinedSqlPath, $CombinedSql, [System.Text.UTF8Encoding]::new($false))
$SqlPath = $CombinedSqlPath

# 3. 规避 CRLF 换行符隐患 (强制转换为标准 LF)
$ShellFiles = Get-ChildItem -Path $AioDir -Filter "*.sh" -Recurse
foreach ($File in $ShellFiles) {
    Write-Host "[Sync] 正在对 $($File.Name) 进行 LF 换行符净化..." -ForegroundColor Yellow
    $Content = [System.IO.File]::ReadAllText($File.FullName)
    $Content = $Content -replace "`r`n", "`n"
    [System.IO.File]::WriteAllText($File.FullName, $Content, [System.Text.UTF8Encoding]::new($false))
}

# 4. 在 iMac 端创建目标目录
Write-Host "[Sync] 正在检查并初始化 iMac 远程目录..." -ForegroundColor Yellow
$SshTarget = "${iMacUser}@${iMacHost}"
ssh -p $Port $SshTarget "mkdir -p $RemoteDir/docker/aio"
if ($LASTEXITCODE -ne 0) {
    Write-Error "无法通过 SSH 连接至 iMac (${SshTarget})，请检查网络或 SSH 服务状态。"
}

# 5. 执行极速同步
if (Test-Path $JarPath) {
    Write-Host "[Sync] 1/3 同步核心后端 Jar 包 (wvp.jar)..." -ForegroundColor Green
    scp -P $Port $JarPath "${SshTarget}:${RemoteDir}/wvp.jar"
}
else {
    Write-Host "[Sync] 1/3 跳过 Jar 包同步（采用远程自包含构建模式）..." -ForegroundColor Yellow
}

Write-Host "[Sync] 2/3 同步数据库初始化脚本 (init.sql)..." -ForegroundColor Green
scp -P $Port $SqlPath "${SshTarget}:${RemoteDir}/init.sql"

Write-Host "[Sync] 3/3 同步 Dockerfile 与编排资产 (docker/aio/)..." -ForegroundColor Green
scp -P $Port -r "${AioDir}/*" "${SshTarget}:${RemoteDir}/docker/aio/"

# 6. 赋予执行权限
Write-Host "[Sync] 修正远程 Shell 脚本可执行权限..." -ForegroundColor Yellow
ssh -p $Port $SshTarget "chmod +x $RemoteDir/docker/aio/*.sh 2>/dev/null || true"

Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "[Sync] 同步全部完成！请登录 iMac 配合机执行镜像打包发布。" -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan
