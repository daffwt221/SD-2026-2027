[CmdletBinding()]
param(
    [ValidateSet('raft04-produtor', 'raft04-no', 'raft04-processador')]
    [string[]] $Projeto = @('raft04-produtor', 'raft04-no', 'raft04-processador')
)

$ErrorActionPreference = 'Stop'

foreach ($taskProject in $Projeto) {
    $taskSource = Join-Path $PSScriptRoot "$taskProject/src/raft04"
    $taskOutput = Join-Path $PSScriptRoot "$taskProject/out"
    $taskFiles = @(Get-ChildItem -LiteralPath $taskSource -Filter '*.java' -File | ForEach-Object FullName)
    if ($taskFiles.Count -eq 0) { throw "Sem fontes em $taskSource" }
    New-Item -ItemType Directory -Path $taskOutput -Force | Out-Null
    & javac --release 11 -encoding UTF-8 -d $taskOutput $taskFiles
    if ($LASTEXITCODE -ne 0) { throw "Falhou a compilacao de $taskProject" }
    Write-Host "$taskProject : compilacao Java 11 OK"
}
