param(
    [ValidateSet('Init', 'Start', 'Connect', 'Status')]
    [string]$Action = 'Status',
    [ValidateSet(1, 2)]
    [int]$Node = 1,
    [string]$IpfsExe = (Join-Path $env:LOCALAPPDATA 'CodexTools/kubo/kubo/ipfs.exe')
)

$ErrorActionPreference = 'Stop'
if (!(Test-Path -LiteralPath $IpfsExe)) {
    $ipfsCommand = Get-Command ipfs -ErrorAction SilentlyContinue
    if (!$ipfsCommand) { throw 'Instalar Kubo ou indicar -IpfsExe com o caminho de ipfs.exe.' }
    $IpfsExe = $ipfsCommand.Source
}
$repoPath = Join-Path $env:LOCALAPPDATA ('SD-COMINDIR01/node' + $Node)
$previousPath = $env:IPFS_PATH
$apiPort = 5000 + $Node
$swarmPort = 4000 + $Node
$gatewayPort = 8079 + $Node

function Invoke-Ipfs {
    & $IpfsExe @args
    if ($LASTEXITCODE -ne 0) { throw ('Kubo terminou com codigo ' + $LASTEXITCODE) }
}

try {
    $env:IPFS_PATH = $repoPath
    switch ($Action) {
        'Init' {
            if (Test-Path -LiteralPath (Join-Path $repoPath 'config')) {
                Write-Output ('No ja inicializado: ' + $repoPath)
                break
            }
            New-Item -ItemType Directory -Path (Split-Path -Parent $repoPath) -Force | Out-Null
            Invoke-Ipfs init --profile=test
            $configPath = Join-Path $repoPath 'config'
            $config = Get-Content -Raw -LiteralPath $configPath | ConvertFrom-Json
            $config.Pubsub | Add-Member -NotePropertyName Enabled -NotePropertyValue $true -Force
            $config.Addresses.API = '/ip4/127.0.0.1/tcp/' + $apiPort
            $config.Addresses.Gateway = '/ip4/127.0.0.1/tcp/' + $gatewayPort
            $config.Addresses.Swarm = @('/ip4/127.0.0.1/tcp/' + $swarmPort)
            $config.Bootstrap = @()
            $config.Discovery.MDNS.Enabled = $false
            $config.Routing | Add-Member -NotePropertyName Type -NotePropertyValue 'none' -Force
            $config.AutoConf | Add-Member -NotePropertyName Enabled -NotePropertyValue $false -Force
            [System.IO.File]::WriteAllText($configPath, ($config | ConvertTo-Json -Depth 100),
                [System.Text.UTF8Encoding]::new($false))
            Write-Output ('No ' + $Node + ' inicializado. API: ' + $apiPort)
        }
        'Start' {
            Invoke-Ipfs daemon
        }
        'Connect' {
            $otherNode = if ($Node -eq 1) { 2 } else { 1 }
            $otherRepo = Join-Path $env:LOCALAPPDATA ('SD-COMINDIR01/node' + $otherNode)
            $otherConfig = Get-Content -Raw -LiteralPath (Join-Path $otherRepo 'config') | ConvertFrom-Json
            $otherPort = 4000 + $otherNode
            Invoke-Ipfs swarm connect ('/ip4/127.0.0.1/tcp/' + $otherPort + '/p2p/' + $otherConfig.Identity.PeerID)
        }
        'Status' {
            Invoke-Ipfs swarm peers
            Invoke-Ipfs pubsub ls
            Invoke-Ipfs pubsub peers rep01-temperaturas
        }
    }
} finally {
    $env:IPFS_PATH = $previousPath
}
