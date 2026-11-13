[CmdletBinding()]
param([string] $Repositorio = '')
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($Repositorio)) { $Repositorio = Join-Path $PSScriptRoot 'resultados/ipfs-demo' }
$taskVersion = '0.39.0'
$taskTool = Join-Path $env:LOCALAPPDATA "CodexTools/kubo-v$taskVersion"
$taskExe = Join-Path $taskTool 'kubo/ipfs.exe'
$taskHash = '5743ac292e762f69d4cb150268c513fbcdf04c6c4840aee16309cbbe27957826'
if (-not (Test-Path -LiteralPath $taskExe)) {
    New-Item -ItemType Directory -Path $taskTool -Force | Out-Null
    $taskZip = Join-Path $taskTool 'kubo.zip'
    & curl.exe -4 -L --fail --silent --show-error --connect-timeout 15 --max-time 180 -H 'Accept: application/octet-stream' 'https://api.github.com/repos/ipfs/kubo/releases/assets/321349232' -o $taskZip
    if ($LASTEXITCODE -ne 0) { throw 'Falhou o download oficial do Kubo' }
    if ((Get-FileHash -LiteralPath $taskZip -Algorithm SHA256).Hash -ne $taskHash) { throw 'SHA256 inesperado' }
    Expand-Archive -LiteralPath $taskZip -DestinationPath $taskTool -Force
}
$taskActual = & $taskExe version
if ($taskActual -ne "ipfs version $taskVersion") { throw "Versao inesperada: $taskActual" }
$taskResponse = & curl.exe --noproxy '*' --silent --fail --max-time 2 -X POST 'http://127.0.0.1:5001/api/v0/version'
if ($LASTEXITCODE -eq 0) {
    if (($taskResponse | ConvertFrom-Json).Version -ne $taskVersion) { throw 'API 5001 ocupada por outra versao do Kubo' }
    Write-Host 'Kubo 0.39.0 ja ativo em 127.0.0.1:5001. Nao foi alterado.'
    return
}
New-Item -ItemType Directory -Path $Repositorio -Force | Out-Null
$taskRepo = (Resolve-Path -LiteralPath $Repositorio).Path
$taskPrevious = $env:IPFS_PATH
try {
    $env:IPFS_PATH = $taskRepo
    if (-not (Test-Path -LiteralPath (Join-Path $taskRepo 'config'))) {
        & $taskExe init
        if ($LASTEXITCODE -ne 0) { throw 'Falhou init' }
        & $taskExe config --json Pubsub.Enabled true
        & $taskExe config --json Discovery.MDNS.Enabled false
        & $taskExe config --json Addresses.Swarm '[]'
        & $taskExe config --json Bootstrap '[]'
        & $taskExe config Addresses.API '/ip4/127.0.0.1/tcp/5001'
        & $taskExe config Addresses.Gateway '/ip4/127.0.0.1/tcp/18080'
        if ($LASTEXITCODE -ne 0) { throw 'Falhou config' }
    }
    $taskDaemon = Start-Process -FilePath $taskExe -ArgumentList @('daemon') -WorkingDirectory $taskRepo -WindowStyle Hidden -RedirectStandardOutput (Join-Path $taskRepo 'daemon-out.txt') -RedirectStandardError (Join-Path $taskRepo 'daemon-err.txt') -PassThru
    $taskDaemon.Id | Set-Content -LiteralPath (Join-Path $taskRepo 'daemon.pid')
    $taskReady = $false
    for ($taskTry = 0; $taskTry -lt 40; $taskTry++) {
        $taskResponse = & curl.exe --noproxy '*' --silent --fail --max-time 1 -X POST 'http://127.0.0.1:5001/api/v0/version'
        if ($LASTEXITCODE -eq 0) { $taskReady = $true; break }
        Start-Sleep -Milliseconds 250
    }
    if (-not $taskReady) { throw "Kubo nao ficou pronto. Consultar $taskRepo/daemon-err.txt" }
    Write-Host "Kubo $taskVersion ativo; PID $($taskDaemon.Id); repositorio $taskRepo"
} finally { $env:IPFS_PATH = $taskPrevious }
