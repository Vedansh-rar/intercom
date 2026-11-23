# Runs the Rider Intercom relay on this PC and exposes it over HTTPS with a free
# Cloudflare quick tunnel (no account needed). Close the window to stop both.
$ErrorActionPreference = 'Stop'
$serverDir = Split-Path -Parent $PSScriptRoot
$port = 8080

function Have($name) { return [bool](Get-Command $name -ErrorAction SilentlyContinue) }
function Refresh-Path {
  $env:Path = [Environment]::GetEnvironmentVariable('Path','Machine') + ';' + [Environment]::GetEnvironmentVariable('Path','User')
}
function Install-With-Winget($id, $label) {
  if (-not (Have 'winget')) { throw "$label is missing and winget is not available. Install $label by hand, then run this again." }
  Write-Host "Installing $label ..."
  winget install --id $id -e --accept-package-agreements --accept-source-agreements
  Refresh-Path
}

if (-not (Have 'node'))        { Install-With-Winget 'OpenJS.NodeJS.LTS' 'Node.js' }
if (-not (Have 'cloudflared')) { Install-With-Winget 'Cloudflare.cloudflared' 'cloudflared' }
if (-not (Have 'node') -or -not (Have 'cloudflared')) {
  throw 'Install finished but the tools are not on PATH yet. Close this window and double-click start-relay.bat again.'
}

Set-Location $serverDir
if (-not (Test-Path (Join-Path $serverDir 'node_modules'))) {
  Write-Host 'Installing relay dependencies ...'
  & npm.cmd ci --omit=dev
  if ($LASTEXITCODE -ne 0) { throw 'npm ci failed' }
}

# Keep Windows awake while the relay runs (only for this process; nothing is changed permanently).
Add-Type -Namespace Win -Name Power -MemberDefinition '[DllImport("kernel32.dll")] public static extern uint SetThreadExecutionState(uint f);'
[void][Win.Power]::SetThreadExecutionState(0x80000001)  # ES_CONTINUOUS | ES_SYSTEM_REQUIRED

$env:PORT = "$port"; $env:HOST = '127.0.0.1'; $env:TRUST_PROXY = '1'
$tmp = Join-Path $env:TEMP 'rider-relay'; New-Item -ItemType Directory -Force $tmp | Out-Null
$tunnelLog = Join-Path $tmp 'tunnel.log'; Remove-Item $tunnelLog -ErrorAction SilentlyContinue
$relay = $null; $tunnel = $null
try {
  $relay = Start-Process node -ArgumentList 'index.js' -WorkingDirectory $serverDir -PassThru -NoNewWindow
  $ok = $false
  for ($i = 0; $i -lt 20 -and -not $ok; $i++) {
    Start-Sleep -Milliseconds 500
    try { $ok = (Invoke-WebRequest "http://127.0.0.1:$port/healthz" -UseBasicParsing -TimeoutSec 2).Content.Trim() -eq 'ok' } catch {}
  }
  if (-not $ok) { throw "The relay did not start on port $port (is something else using it?)." }

  $tunnel = Start-Process cloudflared -ArgumentList "tunnel --no-autoupdate --url http://127.0.0.1:$port" `
    -RedirectStandardError $tunnelLog -RedirectStandardOutput (Join-Path $tmp 'tunnel.out') -PassThru -WindowStyle Hidden
  $url = $null
  for ($i = 0; $i -lt 60 -and -not $url; $i++) {
    Start-Sleep -Milliseconds 500
    if (Test-Path $tunnelLog) {
      $m = Select-String -Path $tunnelLog -Pattern 'https://(?!api\.)[a-z0-9-]+\.trycloudflare\.com' -ErrorAction SilentlyContinue | Select-Object -First 1
      if ($m) { $url = $m.Matches[0].Value }
    }
  }
  if (-not $url) { throw "No tunnel address appeared. See $tunnelLog" }

  $wss = $url -replace '^https://', 'wss://'
  Set-Clipboard -Value $wss
  Write-Host ''
  Write-Host '=== Relay is running ===' -ForegroundColor Green
  Write-Host "Relay address (copied to clipboard):  $wss" -ForegroundColor Green
  Write-Host 'Put it in the app: Settings > Internet ride > Relay address.'
  Write-Host 'This address changes every time you start the relay. Keep this window open and the PC awake.'
  Write-Host 'Close the window (or press Ctrl+C) to stop.'
  while (-not $relay.HasExited -and -not $tunnel.HasExited) { Start-Sleep -Seconds 2 }
  Write-Host 'The relay or the tunnel stopped.' -ForegroundColor Yellow
}
finally {
  foreach ($p in @($tunnel, $relay)) { if ($p -and -not $p.HasExited) { Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue } }
  [void][Win.Power]::SetThreadExecutionState(0x80000000)
}
