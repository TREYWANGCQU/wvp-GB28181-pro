# scripts/generate-wiki.ps1
<#
.SYNOPSIS
    WVP-PRO GitHub Wiki 自动化生成与本地同步脚本 (向后兼容入口)
.DESCRIPTION
    此脚本保留原有的调用接口，底层委托给 scripts/build-wiki-staging.ps1。
    推荐新流程：
    1. 使用本地 AI Skill (/wiki-curator) 或运行 powershell -File scripts/build-wiki-staging.ps1 生成受控暂存区 doc/wiki_staging/；
    2. 提交代码至 master 分支后，由 GitHub Actions 自动部署至 Wiki 仓库。
.PARAMETER WikiDir
    可选：Wiki 本地克隆目录
.PARAMETER Push
    可选：是否在生成后直接执行 git commit 与 git push
.PARAMETER CommitMessage
    可选：提交信息
#>

[CmdletBinding()]
param(
    [string]$WikiDir = "",
    [switch]$Push,
    [string]$CommitMessage = "docs(wiki): automated sync from doc/reaticle_docs"
)

$BuildScript = Join-Path $PSScriptRoot "build-wiki-staging.ps1"
$Params = @{}
if (-not [string]::IsNullOrWhiteSpace($WikiDir)) {
    $Params["SyncToWikiDir"] = $WikiDir
}
if ($Push) {
    $Params["Push"] = $true
}
if (-not [string]::IsNullOrWhiteSpace($CommitMessage)) {
    $Params["CommitMessage"] = $CommitMessage
}

& $BuildScript @Params
