[CmdletBinding()]
param(
    [string] $Sessao = '',
    [string] $Api = 'http://127.0.0.1:5001/api/v0'
)
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($Sessao)) { $Sessao = Join-Path $PSScriptRoot ('resultados/manual-' + (Get-Date -Format 'yyyyMMdd-HHmmss')) }
$taskVersion = & curl.exe --noproxy '*' --silent --fail --max-time 3 -X POST ($Api + '/version')
if ($LASTEXITCODE -ne 0) { throw 'Iniciar Kubo primeiro: preparar-ipfs.ps1' }
$taskBusy = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object { $_.LocalPort -ge 7000 -and $_.LocalPort -le 7008 })
if ($taskBusy.Count) { throw 'Os portos 7000-7008 estao ocupados; terminar a sessao anterior' }
& (Join-Path $PSScriptRoot 'compilar.ps1')
if (Test-Path -LiteralPath $Sessao) { throw 'Escolher uma pasta nova para este ensaio' }
New-Item -ItemType Directory -Path $Sessao | Out-Null
$taskFolder = (Resolve-Path -LiteralPath $Sessao).Path
$taskRuntime = @(& java -cp (Join-Path $PSScriptRoot 'raft04-produtor/out') raft04.JavaRuntime)
if ($LASTEXITCODE -ne 0 -or $taskRuntime.Count -ne 1) { throw 'Nao foi possivel resolver o executavel Java real' }
$taskJava = $taskRuntime[0].Trim()
if (-not (Test-Path -LiteralPath $taskJava)) { throw 'Executavel Java real nao encontrado' }
$taskOwned = [Collections.Generic.List[object]]::new()
$taskQuote = [char]34
try {
    foreach ($taskPartition in 0..2) {
        foreach ($taskNode in 1..3) {
            $taskCp = Join-Path $PSScriptRoot 'raft04-no/out'
            $taskArgs = @('-Dfile.encoding=UTF-8','-Dstdout.encoding=UTF-8',('-Draft04.api='+$Api),'-cp',($taskQuote+$taskCp+$taskQuote),'raft04.PartitionNode',$taskPartition,$taskNode,3)
            $taskName = "p$taskPartition-n$taskNode"
            $taskProcess = Start-Process -FilePath $taskJava -ArgumentList $taskArgs -WorkingDirectory $taskFolder -WindowStyle Hidden -RedirectStandardOutput (Join-Path $taskFolder "consola-$taskName.txt") -RedirectStandardError (Join-Path $taskFolder "erro-$taskName.txt") -PassThru
            $taskOwned.Add($taskProcess)
            [pscustomobject]@{Pid=$taskProcess.Id;Ticks=$taskProcess.StartTime.ToUniversalTime().Ticks;Nome=$taskName} | Export-Csv -LiteralPath (Join-Path $taskFolder 'processos.csv') -NoTypeInformation -Append
        }
    }
    $taskCp = Join-Path $PSScriptRoot 'raft04-processador/out'
    $taskArgs = @('-Dfile.encoding=UTF-8','-Dstdout.encoding=UTF-8','-cp',($taskQuote+$taskCp+$taskQuote),'raft04.AlarmProcessor',3,$Api)
    $taskProcess = Start-Process -FilePath $taskJava -ArgumentList $taskArgs -WorkingDirectory $taskFolder -WindowStyle Hidden -RedirectStandardOutput (Join-Path $taskFolder 'consola-processador.txt') -RedirectStandardError (Join-Path $taskFolder 'erro-processador.txt') -PassThru
    $taskOwned.Add($taskProcess)
    [pscustomobject]@{Pid=$taskProcess.Id;Ticks=$taskProcess.StartTime.ToUniversalTime().Ticks;Nome='processador'} | Export-Csv -LiteralPath (Join-Path $taskFolder 'processos.csv') -NoTypeInformation -Append
    Write-Host "Sessao: $taskFolder"
    Write-Host 'Aguardar cerca de 15 s pelos lideres. Comandos: S02 20.1 | rajada 300 10 | sair'
    Push-Location -LiteralPath $taskFolder
    try { & $taskJava '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' -cp (Join-Path $PSScriptRoot 'raft04-produtor/out') raft04.Producer 3 $Api }
    finally { Pop-Location }
} finally {
    foreach ($taskProcess in $taskOwned) {
        if (-not $taskProcess.HasExited) { $taskProcess.Kill(); $taskProcess.WaitForExit() }
    }
    Write-Host "Processos desta sessao terminados. Dados em $taskFolder"
}
