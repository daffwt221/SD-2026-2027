[CmdletBinding()]
param([Parameter(Mandatory=$true)][string] $Sessao)
$ErrorActionPreference = 'Stop'
$taskFolder = (Resolve-Path -LiteralPath $Sessao).Path
foreach ($taskEntry in (Import-Csv -LiteralPath (Join-Path $taskFolder 'processos.csv'))) {
    $taskProcess = Get-Process -Id ([int]$taskEntry.Pid) -ErrorAction SilentlyContinue
    if ($null -ne $taskProcess -and $taskProcess.ProcessName -eq 'java' -and $taskProcess.StartTime.ToUniversalTime().Ticks -eq [long]$taskEntry.Ticks) {
        Stop-Process -InputObject $taskProcess
    }
}
