<#
.SYNOPSIS
    Seeds InsightHub with a demo user, project, and the sample CSVs (PowerShell).

.DESCRIPTION
    Requires a running stack (see run-demo.ps1). Calls the REST API directly
    using Invoke-RestMethod so no external tools are needed beyond PowerShell 5.1+.

.PARAMETER BackendUrl
    Base URL of the backend API. Default: http://localhost:8080.

.PARAMETER Email / Password
    Demo account credentials. Defaults: demo@insighthub.dev / demo-password-123.
#>
param(
    [string]$BackendUrl = 'http://localhost:8080',
    [string]$Email      = 'demo@insighthub.dev',
    [string]$Password   = 'demo-password-123',
    [string]$FullName   = 'Demo User'
)

$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$ProjectName = 'E-commerce Demo'

try {
    Invoke-RestMethod "$BackendUrl/api/health" -Method GET -TimeoutSec 3 | Out-Null
} catch {
    throw 'Backend is not reachable - start the stack first with .\scripts\run-demo.ps1'
}

Write-Host "Registering $Email ..."
try {
    $register = Invoke-RestMethod "$BackendUrl/api/auth/register" -Method POST `
        -ContentType 'application/json' `
        -Body (@{email=$Email; password=$Password; fullName=$FullName} | ConvertTo-Json)
    Write-Host '  -> created new account'
} catch {
    $status = [int]$_.Exception.Response.StatusCode
    if ($status -ne 409) { throw }
    Write-Host '  -> account already exists'
}

Write-Host 'Logging in ...'
$login = Invoke-RestMethod "$BackendUrl/api/auth/login" -Method POST `
    -ContentType 'application/json' `
    -Body (@{email=$Email; password=$Password} | ConvertTo-Json)
$token = $login.token

Write-Host "Creating project '$ProjectName' ..."
$project = Invoke-RestMethod "$BackendUrl/api/projects" -Method POST `
    -ContentType 'application/json' -Headers @{Authorization="Bearer $token"} `
    -Body (@{name=$ProjectName; description='Created by scripts/seed-demo.ps1 with the sample CSVs.'} | ConvertTo-Json)
$projectId = $project.id

$sampleDir = Resolve-Path samples
foreach ($csv in @('customers.csv','orders.csv','order_items.csv')) {
    $file = Join-Path $sampleDir $csv
    if (-not (Test-Path $file)) { throw "missing sample file $file" }
    Write-Host "Uploading $csv ..."
    if (-not (Get-Command curl.exe -ErrorAction SilentlyContinue)) {
        throw 'curl.exe is required (ships with Windows 10+) for multipart uploads on PowerShell 5.1.'
    }
    $upload = & curl.exe -sS -o NUL -w "%{http_code}" -X POST `
        "$BackendUrl/api/projects/$projectId/datasets" `
        -H "Authorization: Bearer $token" `
        -F "file=@$file"
    if ($LASTEXITCODE -ne 0 -or $upload -ne '201') {
        throw "upload of $csv failed (HTTP $upload)"
    }
}

Write-Host ''
Write-Host "Demo account:  $Email / $Password"
Write-Host "Project:       $ProjectName (id $projectId)"
Write-Host "Open http://localhost:4200 and log in."