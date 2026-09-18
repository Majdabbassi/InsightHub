<#
.SYNOPSIS
    One-command startup for InsightHub on Windows (PowerShell).

.DESCRIPTION
    Creates .env from .env.example (generating a JWT secret), builds and
    starts the stack via Docker Compose, waits for the backend health
    endpoint, and optionally pulls the Ollama model.

.PARAMETER PullModel
    Pull the default Ollama model into the container on first run.

.EXAMPLE
    .\scripts\run-demo.ps1
    .\scripts\run-demo.ps1 -PullModel
#>
param(
    [switch]$PullModel
)

$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw 'docker is required but was not found on PATH.'
}

if (-not (Test-Path .env)) {
    Write-Host 'Creating .env from .env.example (with a generated JWT_SECRET)...'
    Copy-Item .env.example .env
    if (Get-Command python -ErrorAction SilentlyContinue) {
        $secret = python -c 'import secrets; print(secrets.token_urlsafe(48))'
    } else {
        $bytes = [byte[]]::new(48)
        [System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
        $secret = [Convert]::ToBase64String($bytes).Replace('+','-').Replace('/','_').TrimEnd('=')
    }
    (Get-Content .env) -replace '^JWT_SECRET=.*', "JWT_SECRET=$secret" | Set-Content .env
    Write-Host "  -> JWT_SECRET written."
}

Write-Host 'Building and starting services (first build takes a while)...'
docker compose up -d --build
if ($LASTEXITCODE -ne 0) { throw 'docker compose up failed.' }

Write-Host 'Waiting for the backend to become healthy...'
for ($i = 0; $i -lt 120; $i++) {
    try {
        $resp = Invoke-WebRequest -Uri 'http://localhost:8080/api/health' -UseBasicParsing -TimeoutSec 2 -ErrorAction SilentlyContinue
        if ($resp.StatusCode -eq 200) {
            Write-Host 'Backend is up.'
            break
        }
    } catch {}
    Start-Sleep -Seconds 1
}

if ($PullModel) {
    $modelLine = Select-String -Path .env -Pattern '^OLLAMA_MODEL=' | Select-Object -Last 1
    $model = if ($modelLine) { ($modelLine.Line -split '=', 2)[1] } else { 'llama3.2:3b' }
    Write-Host "Pulling Ollama model '${model}' (first run only; may take minutes)..."
    docker exec -it data-ollama ollama pull $model
}

Write-Host ''
Write-Host 'InsightHub is running:'
Write-Host '  Frontend     http://localhost:4200'
Write-Host '  Backend API  http://localhost:8080/api'
Write-Host '  phpMyAdmin   http://localhost:8081'
Write-Host ''
Write-Host 'Next: create an account in the UI, then upload the demo CSVs from samples/.'
Write-Host 'Or run the full seed: .\scripts\seed-demo.ps1'