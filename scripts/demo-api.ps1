param(
    [string]$BaseUrl = "http://127.0.0.1:18080/sg-user-service",
    [string]$TenantCode = "gitee",
    [long]$ExaminationId = 1,
    [long]$ExpectedStudentId = 2,
    [switch]$NoPause
)

$ErrorActionPreference = "Stop"

function Get-DemoPassword([string]$variable, [string]$label) {
    $value = [Environment]::GetEnvironmentVariable($variable)
    if ($value) { return $value }
    $secure = Read-Host $label -AsSecureString
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
}

function Assert-Demo([bool]$condition, [string]$message) {
    if (-not $condition) { throw $message }
}

function Login-Demo([string]$username, [string]$password) {
    $response = Invoke-RestMethod -Method Post -Uri "$BaseUrl/login" -Headers @{"Tenant-Code" = $TenantCode} -Body @{username = $username; password = $password} -ContentType "application/x-www-form-urlencoded" -TimeoutSec 15
    Assert-Demo ($response.code -eq 0 -and [bool]$response.result.token) "Demo login failed for $username"
    return @{"Tenant-Code" = $TenantCode; Authorization = $response.result.token}
}

$resultDirectory = Join-Path (Split-Path $PSScriptRoot -Parent) ".run"
$resultPath = Join-Path $resultDirectory "demo-result.json"
New-Item -ItemType Directory -Path $resultDirectory -Force | Out-Null

try {
$studentPassword = Get-DemoPassword "SG_DEMO_STUDENT_PASSWORD" "Student demo password"
$adminPassword = Get-DemoPassword "SG_DEMO_ADMIN_PASSWORD" "Other-user demo password"
$studentHeaders = Login-Demo "preview" $studentPassword
$adminHeaders = Login-Demo "admin" $adminPassword

# The server must derive ownership from the token, not the forged userId.
$startBody = @{examinationId = $ExaminationId; userId = 1} | ConvertTo-Json
$start = Invoke-RestMethod -Method Post -Uri "$BaseUrl/v1/examRecord/start" -Headers $studentHeaders -ContentType "application/json" -Body $startBody -TimeoutSec 20
Assert-Demo ($start.code -eq 0 -and [bool]$start.result.examRecord.id) "Starting the demo exam failed"
$recordId = [long]$start.result.examRecord.id
$subjectId = [long]$start.result.subjectDto.id
Assert-Demo ([long]$start.result.examRecord.userId -eq $ExpectedStudentId) "Server trusted a forged student ID"

$answerBody = ConvertTo-Json -InputObject @(@{
    id = 999999
    examRecordId = $recordId
    subjectId = $subjectId
    type = 0
    answer = "C"
}) -Compress
$submitUrl = "$BaseUrl/v1/answer/submitAll"
$foreign = Invoke-RestMethod -Method Post -Uri $submitUrl -Headers $adminHeaders -ContentType "application/json" -Body $answerBody -TimeoutSec 20
Assert-Demo ($foreign.code -eq 0 -and $foreign.result -eq $false) "Other user submitted the student's record"
$owner = Invoke-RestMethod -Method Post -Uri $submitUrl -Headers $studentHeaders -ContentType "application/json" -Body $answerBody -TimeoutSec 20
Assert-Demo ($owner.code -eq 0 -and $owner.result -eq $true) "Owner's submission failed"
$retry = Invoke-RestMethod -Method Post -Uri $submitUrl -Headers $studentHeaders -ContentType "application/json" -Body $answerBody -TimeoutSec 20
Assert-Demo ($retry.code -eq 0 -and $retry.result -eq $false) "Duplicate submission was accepted"

$record = Invoke-RestMethod -Uri "$BaseUrl/v1/examRecord/$recordId" -Headers $studentHeaders -TimeoutSec 10
Assert-Demo ($record.code -eq 0 -and $record.result.submitStatus -eq 3 -and $record.result.score -eq 5) "Persisted score or status is unexpected"
$rank = Invoke-RestMethod -Uri "$BaseUrl/v1/answer/rankInfo?examinationId=$ExaminationId" -Headers $studentHeaders -TimeoutSec 10
$rankEntry = @($rank.result) | Where-Object { [long]$_.userId -eq $ExpectedStudentId } | Select-Object -First 1
Assert-Demo ($rank.code -eq 0 -and $null -ne $rankEntry -and $rankEntry.score -eq 5) "Redis ranking did not reflect the committed score"

$result = [ordered]@{
    recordId = $recordId
    ownerId = [long]$start.result.examRecord.userId
    foreignSubmissionRejected = $true
    ownerSubmissionAccepted = $true
    duplicateSubmissionRejected = $true
    storedStatus = [int]$record.result.submitStatus
    storedScore = [double]$record.result.score
    rankScore = [double]$rankEntry.score
} | ConvertTo-Json
$result | Set-Content -LiteralPath $resultPath -Encoding UTF8
Write-Host "Demo passed. Result saved to: $resultPath"
Write-Output $result
} catch {
    $failure = [ordered]@{status = "failed"; message = $_.Exception.Message} | ConvertTo-Json
    $failure | Set-Content -LiteralPath $resultPath -Encoding UTF8
    Write-Host "Demo failed. Reason saved to: $resultPath" -ForegroundColor Red
    Write-Output $failure
    throw
} finally {
    if (-not $NoPause) { [void](Read-Host "Press Enter to close this window") }
}
