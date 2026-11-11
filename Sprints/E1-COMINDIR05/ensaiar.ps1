[CmdletBinding()]
param(
    [ValidateSet('unit','smoke','9a','9b','9c','9d','9e','all','falhas','quorum','memoria','desatualizado','lote-antigo','relogios-zero')]
    [string] $Ensaio = 'unit',
    [string] $Pasta = '',
    [string] $Api = 'http://127.0.0.1:5001/api/v0'
)
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($Pasta)) { $Pasta = Join-Path $PSScriptRoot ('resultados/ensaio-' + (Get-Date -Format 'yyyyMMdd-HHmmss')) }
& (Join-Path $PSScriptRoot 'compilar.ps1')
$taskOut = Join-Path $PSScriptRoot 'testes/out'
New-Item -ItemType Directory -Path $taskOut -Force | Out-Null
$taskSources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'testes') -Filter '*.java' -File | ForEach-Object FullName)
& javac --release 11 -encoding UTF-8 -cp (Join-Path $PSScriptRoot 'raft04-no/out') -d $taskOut $taskSources
if ($LASTEXITCODE -ne 0) { throw 'Falhou compilacao dos ensaios' }
$taskCp = (Join-Path $PSScriptRoot 'raft04-no/out') + [IO.Path]::PathSeparator + $taskOut
if ($Ensaio -eq 'unit') {
    New-Item -ItemType Directory -Path $Pasta -Force | Out-Null
    & java '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' -cp $taskCp raft04.RaftStateTest $Pasta
} else {
    $taskVersion = & curl.exe --noproxy '*' --fail --silent --show-error --max-time 3 -X POST ($Api + '/version')
    if ($LASTEXITCODE -ne 0) { throw 'Iniciar Kubo primeiro: preparar-ipfs.ps1' }
    if (($taskVersion | ConvertFrom-Json).Version -ne '0.39.0') { throw 'Os ensaios estao fixados ao Kubo 0.39.0; usar um repositorio privado dessa versao' }
    if ($Ensaio -in @('falhas','quorum','memoria','desatualizado','lote-antigo','relogios-zero')) {
        $taskCase = $Ensaio
        if ($Ensaio -eq 'falhas') { $taskCase = 'all' }
        & java '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' -cp $taskCp raft04.FaultExperiments $taskCase $PSScriptRoot $Pasta $Api
    } else {
        & java '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' -cp $taskCp raft04.RaftExperiments $Ensaio $PSScriptRoot $Pasta $Api
    }
}
if ($LASTEXITCODE -ne 0) { throw "Ensaio terminou com codigo $LASTEXITCODE. Consultar $Pasta" }
