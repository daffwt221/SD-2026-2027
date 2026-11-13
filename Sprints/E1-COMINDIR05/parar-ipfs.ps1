[CmdletBinding()]
param([string] $Repositorio = '')
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($Repositorio)) { $Repositorio = Join-Path $PSScriptRoot 'resultados/ipfs-demo' }
$taskRepo = (Resolve-Path -LiteralPath $Repositorio).Path
if (-not (Test-Path -LiteralPath (Join-Path $taskRepo 'daemon.pid'))) { throw 'Falta daemon.pid deste repositorio' }
$taskPrevious = $env:IPFS_PATH
try {
    $env:IPFS_PATH = $taskRepo
    & (Join-Path $env:LOCALAPPDATA 'CodexTools/kubo-v0.39.0/kubo/ipfs.exe') shutdown
    if ($LASTEXITCODE -ne 0) { throw 'Nao foi possivel terminar este daemon' }
} finally { $env:IPFS_PATH = $taskPrevious }
