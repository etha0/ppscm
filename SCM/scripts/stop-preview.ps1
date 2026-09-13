param([string]$RuntimeName = '.preview')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$runtimeRoot = Join-Path $projectRoot $RuntimeName
$pidFile = Join-Path $runtimeRoot 'server.pid'
if (!(Test-Path -LiteralPath $pidFile)) { Write-Output 'No preview PID file.'; exit }
$previewPid = [int](Get-Content -LiteralPath $pidFile)
$process = Get-CimInstance Win32_Process -Filter "ProcessId = $previewPid"
if ($process -and $process.CommandLine.Contains("-Dcatalina.base=$runtimeRoot")) {
    [System.Diagnostics.Process]::GetProcessById($previewPid).Kill()
    Write-Output 'Preview stopped.'
} elseif ($process) { throw 'PID belongs to another process; it was not stopped.' }
