param([int]$Port = 18080)

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
$base = "http://127.0.0.1:$Port/sg-user-service"
$headers = @{"Tenant-Code" = "gitee"}
$results = @()

$document = Invoke-RestMethod "$base/v3/api-docs" -TimeoutSec 15
$pathCount = @($document.paths.PSObject.Properties).Count
if ($pathCount -lt 1) { throw "OpenAPI document has no paths." }
$results += @{check="OpenAPI"; passed=$true; paths=$pathCount}

$demo = Invoke-WebRequest -Uri "$base/submission-demo" -UseBasicParsing -TimeoutSec 10
if ($demo.StatusCode -ne 200 -or $demo.Content -notlike "*考试交卷工作台*") {
    throw "Chinese submission demo is unavailable."
}
$results += @{check="Chinese browser demo"; passed=$true}

$config = Invoke-RestMethod "$base/v1/config/getDefaultSysConfig" -Headers $headers -TimeoutSec 10
if ($config.code -ne 0 -or $null -eq $config.result) { throw "Public configuration query failed." }
$results += @{check="Public database-backed query"; passed=$true}

$denied = Invoke-RestMethod "$base/v1/config/list" -Headers $headers -TimeoutSec 10
if ($denied.code -eq 0 -or $denied.result -ne 401) { throw "Protected query was not rejected." }
$results += @{check="Unauthenticated query rejected (upstream business error 401)"; passed=$true}

$client = New-Object System.Net.Sockets.TcpClient
try {
    if (-not $client.ConnectAsync("127.0.0.1", 6379).Wait(3000)) { throw "Redis connection timed out." }
    $stream = $client.GetStream()
    $stream.ReadTimeout = 3000
    $command = [Text.Encoding]::ASCII.GetBytes("PING`r`n")
    $stream.Write($command, 0, $command.Length)
    $buffer = New-Object byte[] 64
    $length = $stream.Read($buffer, 0, $buffer.Length)
    $reply = [Text.Encoding]::ASCII.GetString($buffer, 0, $length)
    if ($reply.Trim() -ne "+PONG") { throw "Unexpected Redis PING response." }
    $results += @{check="Embedded Redis PING"; passed=$true}
}
finally { $client.Dispose() }

New-Item -ItemType Directory -Path (Join-Path $root ".run") -Force | Out-Null
$results | ConvertTo-Json | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $root ".run/api-smoke.json")
$results | ForEach-Object { "PASS: $($_.check)" }
