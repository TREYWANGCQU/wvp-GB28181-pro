# scripts/sync-infra-to-imac.ps1
<#
.SYNOPSIS
    WVP-PRO 调试环境中间件与流媒体配置极速同步脚本 (Windows 11 -> iMac 配合机)
.DESCRIPTION
    依据 doc/reaticle_docs/compile.md 第 3 节规范：
    1. 自动提取并同步最新的基础全量建表 (01-init.sql) 与 ONVIF 协议增量表 (02-onvif.sql)；
    2. 自动化将 MySQL、Redis、ZLMediaKit 配置文件与 SQL 转换为 Linux 标准 LF 换行；
    3. 通过 SCP / SSH 将 docker/infra 目录资产增量同步至 iMac 配合机的中间件编排目录 (~/wvp-infra)；
    4. 支持一键远程拉起容器 (-Up) 或热升级 ONVIF 表结构 (-UpgradeOnvif)。
.PARAMETER iMacHost
    配合机 IP 地址，默认为 192.168.1.50
.PARAMETER iMacUser
    配合机 SSH 用户名，默认为 reaticle
.PARAMETER RemoteDir
    配合机远程工作空间，默认为 ~/wvp-infra
.PARAMETER Port
    SSH 端口，默认为 22
.PARAMETER Up
    同步完成后直接远程执行 docker compose up -d
.PARAMETER Restart
    同步完成后直接远程重启全部容器 docker compose restart
.PARAMETER UpgradeOnvif
    在已有 MySQL 容器中执行 ONVIF 增量表热升级
#>

[CmdletBinding()]
param(
    [string]$iMacHost = "192.168.120.11",
    [string]$iMacUser = "reaticle",
    [string]$RemoteDir = "~/project/wvp-infra",
    [int]$Port = 22,
    [switch]$Up,
    [switch]$Restart,
    [switch]$UpgradeOnvif
)

$ErrorActionPreference = "Stop"

# 1. 定位工程根目录与调试基础设施目录
$ProjectRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$InfraDir = Join-Path $ProjectRoot "docker\infra"

Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "[Sync-Infra] WVP-PRO 调试中间件同步流水线启动..." -ForegroundColor Cyan
Write-Host "[Sync-Infra] 工程根路径: $ProjectRoot" -ForegroundColor Gray
Write-Host "[Sync-Infra] 配置源路径: $InfraDir" -ForegroundColor Gray
Write-Host "[Sync-Infra] 目标主机  : ${iMacUser}@${iMacHost}:${RemoteDir}" -ForegroundColor Gray
Write-Host "============================================================" -ForegroundColor Cyan

if (-not (Test-Path $InfraDir)) {
    Write-Error "未找到调试基础设施配置目录: $InfraDir"
}

# 2. 数据库脚本检查与同步归位
$BaseSqlPath = Join-Path $ProjectRoot "数据库\2.7.4\初始化-mysql-2.7.4.sql"
$OnvifSqlPath = Join-Path $ProjectRoot "数据库\2.7.4\增量-onvif.sql"
$InitDbDir = Join-Path $InfraDir "mysql\initdb"

if (-not (Test-Path $InitDbDir)) {
    New-Item -ItemType Directory -Path $InitDbDir -Force | Out-Null
}

if (Test-Path $BaseSqlPath) {
    Copy-Item -Path $BaseSqlPath -Destination (Join-Path $InitDbDir "01-init.sql") -Force
    Write-Host "[Sync-Infra] 已同步基础全量表结构 -> 01-init.sql" -ForegroundColor Green
}
else {
    Write-Warning "未找到基础全量表结构文件: $BaseSqlPath"
}

if (Test-Path $OnvifSqlPath) {
    Copy-Item -Path $OnvifSqlPath -Destination (Join-Path $InitDbDir "02-onvif.sql") -Force
    Write-Host "[Sync-Infra] 已同步 ONVIF 增量表结构 -> 02-onvif.sql" -ForegroundColor Green
}
else {
    Write-Warning "未找到 ONVIF 增量表结构文件: $OnvifSqlPath"
}

# 3. 规避 CRLF 换行符隐患 (强制转换为标准 LF)
Write-Host "[Sync-Infra] 正在对配置文件与 SQL 脚本进行 LF 换行符净化..." -ForegroundColor Yellow
$TextFiles = Get-ChildItem -Path $InfraDir -Recurse -File | Where-Object {
    $_.Extension -match "^\.(cnf|conf|ini|yml|yaml|sql|sh|txt|md)$"
}

foreach ($File in $TextFiles) {
    $Content = [System.IO.File]::ReadAllText($File.FullName)
    if ($Content.Contains("`r`n")) {
        $Content = $Content -replace "`r`n", "`n"
        [System.IO.File]::WriteAllText($File.FullName, $Content, [System.Text.UTF8Encoding]::new($false))
    }
}

# 4. 在 iMac 端创建目标目录结构
Write-Host "[Sync-Infra] 正在检查并初始化 iMac 远程目录结构..." -ForegroundColor Yellow
$SshTarget = "${iMacUser}@${iMacHost}"
$MkdirCmd = "mkdir -p $RemoteDir/{mysql/conf.d,mysql/initdb,redis,zlm}"
ssh -p $Port $SshTarget $MkdirCmd
if ($LASTEXITCODE -ne 0) {
    Write-Error "无法通过 SSH 连接至 iMac (${SshTarget})，请检查网络联通性与 SSH 服务状态。"
}

# 5. 执行极速增量资产同步
Write-Host "[Sync-Infra] 1/4 同步 Docker Compose 编排文件 (docker-compose.yml)..." -ForegroundColor Green
scp -P $Port "${InfraDir}/docker-compose.yml" "${SshTarget}:${RemoteDir}/docker-compose.yml"

Write-Host "[Sync-Infra] 2/4 同步 MySQL 定制配置与建表初始化脚本 (mysql/)..." -ForegroundColor Green
scp -P $Port -r "${InfraDir}/mysql" "${SshTarget}:${RemoteDir}/"

Write-Host "[Sync-Infra] 3/4 同步 Redis 配置文件 (redis/redis.conf)..." -ForegroundColor Green
scp -P $Port -r "${InfraDir}/redis" "${SshTarget}:${RemoteDir}/"

Write-Host "[Sync-Infra] 4/4 同步 ZLMediaKit 流媒体与 WebRTC 配置文件 (zlm/config.ini)..." -ForegroundColor Green
scp -P $Port -r "${InfraDir}/zlm" "${SshTarget}:${RemoteDir}/"

Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "[Sync-Infra] 配置文件与初始化脚本同步全部完成！" -ForegroundColor Green
Write-Host "============================================================" -ForegroundColor Cyan

# 6. 可选远程容器操作
if ($Up) {
    Write-Host "[Sync-Infra] 正在远程拉起中间件与流媒体容器 (docker compose up -d)..." -ForegroundColor Cyan
    ssh -p $Port $SshTarget "cd $RemoteDir && docker compose up -d"
    ssh -p $Port $SshTarget "cd $RemoteDir && docker compose ps"
}
elseif ($Restart) {
    Write-Host "[Sync-Infra] 正在远程重启中间件与流媒体容器 (docker compose restart)..." -ForegroundColor Cyan
    ssh -p $Port $SshTarget "cd $RemoteDir && docker compose restart"
    ssh -p $Port $SshTarget "cd $RemoteDir && docker compose ps"
}

if ($UpgradeOnvif) {
    Write-Host "[Sync-Infra] 正在为已有 MySQL 容器热升级 ONVIF 增量表..." -ForegroundColor Cyan
    ssh -p $Port $SshTarget "docker exec -i wvp-mysql mysql -uroot -proot wvp < $RemoteDir/mysql/initdb/02-onvif.sql"
    if ($LASTEXITCODE -eq 0) {
        Write-Host "[Sync-Infra] ONVIF 增量表升级成功！" -ForegroundColor Green
    }
}

Write-Host ""
Write-Host "提示: 若要在 iMac 终端手动管理容器，请在配合机执行:" -ForegroundColor Yellow
Write-Host "  cd $RemoteDir" -ForegroundColor White
Write-Host "  docker compose up -d        # 启动" -ForegroundColor White
Write-Host "  docker compose ps           # 状态" -ForegroundColor White
Write-Host "  docker compose logs -f wvp-zlm  # 查看流媒体日志" -ForegroundColor White
Write-Host ""
