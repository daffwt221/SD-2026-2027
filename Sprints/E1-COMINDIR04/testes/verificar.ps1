$ErrorActionPreference = 'Stop'
$taskProject = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
foreach ($taskRole in @('produtor', 'lider', 'replica', 'processador')) {
    $taskProjectDirectory = Join-Path $taskProject "rep04-$taskRole"
    $taskSources = @(Get-ChildItem -LiteralPath (Join-Path $taskProjectDirectory 'src/rep04') -Filter '*.java' -File | ForEach-Object FullName)
    $taskOutput = Join-Path $taskProjectDirectory 'out'
    New-Item -ItemType Directory -Path $taskOutput -Force | Out-Null
    & javac --release 11 -encoding UTF-8 -d $taskOutput $taskSources
    if ($LASTEXITCODE -ne 0) { throw "Falha de compilação: $taskRole" }
}
$taskTestOutput = Join-Path $PSScriptRoot 'out'
New-Item -ItemType Directory -Path $taskTestOutput -Force | Out-Null
$taskReplicaSources = @(Get-ChildItem -LiteralPath (Join-Path $taskProject 'rep04-replica/src/rep04') -Filter '*.java' -File | ForEach-Object FullName)
$taskProducerSource = Join-Path $taskProject 'rep04-produtor/src/rep04/Producer.java'
& javac --release 11 --add-modules jdk.httpserver -encoding UTF-8 -d $taskTestOutput $taskReplicaSources $taskProducerSource (Join-Path $PSScriptRoot 'Rep04SmokeTest.java') (Join-Path $PSScriptRoot 'HeartbeatCheckTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Falha de compilação dos testes.' }
$taskRun = Join-Path $taskTestOutput ('execucao-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
& java -cp $taskTestOutput rep04.HeartbeatCheckTest (Join-Path $taskRun 'metricas')
if ($LASTEXITCODE -ne 0) { throw "Metricas incorretas. Artefactos: $taskRun" }
& java --add-modules jdk.httpserver -cp $taskTestOutput rep04.Rep04SmokeTest $taskProject $taskRun
if ($LASTEXITCODE -ne 0) { throw "Os testes falharam. Artefactos: $taskRun" }
