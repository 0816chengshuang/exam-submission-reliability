param(
    [ValidateSet("Build", "Test", "Start", "Stop", "Status")]
    [string]$Action = "Test",
    [ValidateRange(1024, 65535)]
    [int]$Port = 18080
)

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
$stateFile = Join-Path $root ".run/runtime.json"
$baseUrl = "http://127.0.0.1:$Port/sg-user-service"
$oldJavaHome = $env:JAVA_HOME
Push-Location $root
try {
    New-Item -ItemType Directory -Path ".run" -Force | Out-Null
    if ($Action -in @("Stop", "Status")) {
        if (-not (Test-Path -LiteralPath $stateFile)) { Write-Output "No managed local server."; return }
        $state = Get-Content -Raw -LiteralPath $stateFile | ConvertFrom-Json
        $process = Get-Process -Id $state.pid -ErrorAction SilentlyContinue
        if (-not $process) { Write-Output "Local server is stopped."; return }
        $recordedStart = ([datetimeoffset]$state.startedUtc).UtcDateTime
        if ([math]::Abs(($process.StartTime.ToUniversalTime() - $recordedStart).TotalSeconds) -gt 2) {
            throw "PID was reused; refusing to manage another process."
        }
        if ($Action -eq "Status") {
            Write-Output "$($state.baseUrl)/submission-demo"
            Write-Output "$($state.baseUrl)/swagger-ui/index.html"
            return
        }
        $children = @(Get-CimInstance Win32_Process -Filter "ParentProcessId=$($state.pid)" |
            Where-Object { $_.Name -like "redis-server*" })
        $listener = Get-NetTCPConnection -LocalPort ([uri]$state.baseUrl).Port -State Listen -ErrorAction SilentlyContinue
        if ($listener) {
            if ($listener.OwningProcess -ne $state.pid) { throw "The port belongs to another process." }
            $response = Invoke-RestMethod -Method Post -Uri "$($state.baseUrl)/actuator/shutdown" `
                -ContentType "application/json" -Body "{}" -TimeoutSec 10
            if ($response.message -notlike "Shutting down*") { throw "Shutdown request was not accepted." }
        }
        # Some upstream background threads survive Spring context shutdown.
        Wait-Process -Id $state.pid -Timeout 10 -ErrorAction SilentlyContinue
        $process.Refresh()
        if (-not $process.HasExited) { Stop-Process -Id $state.pid }
        foreach ($child in $children) {
            $current = Get-CimInstance Win32_Process -Filter "ProcessId=$($child.ProcessId)"
            if ($current -and $current.ParentProcessId -eq $state.pid -and $current.CreationDate -eq $child.CreationDate) {
                Stop-Process -Id $child.ProcessId -ErrorAction SilentlyContinue
            }
        }
        Write-Output "Local server stopped."
        return
    }
    $jdk = Get-ChildItem -Path (Join-Path $root "../../.tools") -Directory -Filter "jdk-17*" -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($jdk) { $env:JAVA_HOME = $jdk.FullName }
    elseif (-not $env:JAVA_HOME) { throw "Java 17 is required. Set JAVA_HOME or install it under the workspace .tools directory." }
    $java = Join-Path $env:JAVA_HOME "bin/java.exe"
    $settings = & $java -XshowSettings:properties -version 2>&1
    $nativeLine = $settings | Select-String "^\s*native.encoding\s*=\s*(.+)$" | Select-Object -First 1
    $nativeEncoding = if ($nativeLine) { $nativeLine.Matches[0].Groups[1].Value.Trim() } else { "UTF-8" }
    if ($Action -in @("Build", "Test")) {
        # Gradle 7 writes Java worker arg-files using the daemon charset on Windows.
        $arguments = @("--no-daemon", "--max-workers=2", "--console=plain", "-Dorg.gradle.jvmargs=-Dfile.encoding=$nativeEncoding")
        if ($Action -eq "Build") { $arguments += ":sg-user-service:bootJar" }
        else {
            $arguments += @(
                ":sg-user-service:test",
                "--tests", "com.github.tangyi.user.AesTests",
                "--tests", "com.github.tangyi.baseline.LocalDatabaseBaselineTests",
                "--tests", "com.github.tangyi.baseline.SubmissionClaimTests",
                "--tests", "com.github.tangyi.baseline.SubmissionValidationTests",
                "--tests", "com.github.tangyi.baseline.SubmissionSideEffectTests",
                "--tests", "com.github.tangyi.baseline.SubmissionRecoveryTests"
            )
        }
        & .\gradlew.bat @arguments 2>&1 | Tee-Object -FilePath ".run/baseline-$($Action.ToLowerInvariant()).log"
        if ($LASTEXITCODE -ne 0) { throw "Gradle $Action failed. See .run for logs." }
        return
    }
    foreach ($checkPort in @($Port, 6379)) {
        if (Get-NetTCPConnection -LocalPort $checkPort -State Listen -ErrorAction SilentlyContinue) {
            throw "Port $checkPort is occupied. No existing process has been stopped."
        }
    }
    $jar = "sg-user-service/build/libs/sg-user-service-0.0.15.jar"
    if (-not (Test-Path -LiteralPath $jar)) { throw "Run this script with -Action Build first." }
    $arguments = @(
        "-Dfile.encoding=UTF-8", "-jar", $jar,
        "--spring.config.location=file:./config-repo/application.yml,file:./config-repo/sg-user-service.yml",
        "--spring.profiles.active=dev", "--server.address=127.0.0.1", "--server.port=$Port",
        "--spring.datasource.url=jdbc:h2:file:./.local/h2/sg-exam-user;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;NON_KEYWORDS=USER,VALUE;AUTO_SERVER=TRUE",
        "--minio.enabled=false", "--spring.h2.console.enabled=false",
        "--portfolio.demo.enabled=true",
        "--exam.submission.recovery-enabled=true",
        "--logging.level.com.github.tangyi=INFO",
        "--management.endpoint.shutdown.enabled=true",
        "--management.endpoints.web.exposure.include=health,prometheus,shutdown"
    )
    $process = Start-Process -FilePath $java -ArgumentList $arguments -WorkingDirectory $root -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $root ".run/server.log") `
        -RedirectStandardError (Join-Path $root ".run/server-error.log")
    @{pid=$process.Id; startedUtc=$process.StartTime.ToUniversalTime().ToString("o"); baseUrl=$baseUrl} |
        ConvertTo-Json | Set-Content -Encoding UTF8 -LiteralPath $stateFile
    $deadline = (Get-Date).AddSeconds(90)
    while ((Get-Date) -lt $deadline) {
        $process.Refresh()
        if ($process.HasExited) { throw "Server exited. See .run/server.log and server-error.log." }
        try {
            $response = Invoke-WebRequest -Uri "$baseUrl/v3/api-docs" -TimeoutSec 3 -UseBasicParsing
            if ($response.StatusCode -eq 200) {
                Write-Output "$baseUrl/submission-demo"
                Write-Output "$baseUrl/swagger-ui/index.html"
                return
            }
        } catch { Start-Sleep -Milliseconds 750 }
    }
    throw "Server readiness timed out; inspect .run logs and use -Action Stop to stop it."
}
finally {
    $env:JAVA_HOME = $oldJavaHome
    Pop-Location
}
