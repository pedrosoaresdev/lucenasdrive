# Sobe o ambiente local do Lucena Drive no Windows (PowerShell), sem Docker:
# Firebase Emulator Suite, backend (perfil dev) e os 3 portais, cada um em sua própria janela.
# Requisitos: Java 21, Maven 3.9+, Node 24 (ou >= 22.22.3), Git.
# Uso (na pasta do projeto):  Set-ExecutionPolicy -Scope Process Bypass; .\scripts\dev-up.ps1
$ErrorActionPreference = 'Stop'
# Caminho completo do PowerShell em uso (não depende de 'powershell' estar no PATH).
$Shell = (Get-Process -Id $PID).Path
$Root = Split-Path -Parent $PSScriptRoot
$Logs = Join-Path $Root '.dev-logs'
New-Item -ItemType Directory -Force -Path $Logs | Out-Null

$OwnerEmail = 'dono@lucena.test'
$OwnerPassword = 'SenhaDono2026x'
$OwnerCpf = '390.533.447-05'   # CPF fictício, válido apenas matematicamente

function Test-Command($name, $hint) {
  if (-not (Get-Command $name -ErrorAction SilentlyContinue)) { throw "Comando '$name' não encontrado. $hint" }
}
Test-Command java 'Instale o Java 21 (ex.: Eclipse Temurin).'
Test-Command mvn 'Instale o Maven 3.9+ e adicione ao PATH.'
Test-Command node 'Instale o Node.js 24 LTS.'
Test-Command npm 'Instale o Node.js 24 LTS.'

function Wait-Url($url) {
  for ($i = 0; $i -lt 180; $i++) {
    try { Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 2 | Out-Null; return } catch { Start-Sleep -Seconds 1 }
  }
  throw "Tempo esgotado aguardando $url"
}

# Chaves de DESENVOLVIMENTO persistidas localmente (fora do git).
$KeysFile = Join-Path $Logs 'dev-keys.env'
if (-not (Test-Path $KeysFile)) {
  $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
  $k1 = New-Object byte[] 32; $rng.GetBytes($k1)
  $k2 = New-Object byte[] 32; $rng.GetBytes($k2)
  "LUCENA_DATA_ENCRYPTION_KEY=$([Convert]::ToBase64String($k1))`nLUCENA_ADMIN_MFA_TOKEN_SECRET=$([Convert]::ToBase64String($k2))" |
    Set-Content -Encoding ASCII $KeysFile
}
$Keys = @{}
Get-Content $KeysFile | ForEach-Object { $p = $_.Split('=', 2); if ($p.Length -eq 2) { $Keys[$p[0]] = $p[1] } }

Write-Host '> Instalando dependências (primeira vez demora alguns minutos)...'
if (-not (Test-Path (Join-Path $Root 'firebase\node_modules'))) { npm ci --prefix (Join-Path $Root 'firebase') }
# Reinstala quando o package-lock.json mudou (ex.: nova biblioteca, como o mapa OpenStreetMap).
function Test-NeedsInstall($dir) {
  $marker = Join-Path $dir 'node_modules\.package-lock.json'
  if (-not (Test-Path $marker)) { return $true }
  return (Get-Item (Join-Path $dir 'package-lock.json')).LastWriteTime -gt (Get-Item $marker).LastWriteTime
}
if (Test-NeedsInstall (Join-Path $Root 'frontend')) { npm ci --prefix (Join-Path $Root 'frontend') }

Write-Host '> Firebase Emulator Suite (nova janela)'
Start-Process $Shell -ArgumentList '-NoExit', '-Command',
  "cd '$Root\firebase'; npx firebase emulators:start --project demo-lucena-drive"
Wait-Url 'http://127.0.0.1:9099'
Wait-Url 'http://127.0.0.1:8085'

Write-Host "> Conta do proprietário no Auth Emulator ($OwnerEmail)"
try {
  Invoke-RestMethod -Method Post -ContentType 'application/json' `
    -Uri 'http://127.0.0.1:9099/identitytoolkit.googleapis.com/v1/accounts:signUp?key=demo-api-key' `
    -Body (@{ email = $OwnerEmail; password = $OwnerPassword } | ConvertTo-Json) | Out-Null
} catch { Write-Host '  (conta já existia)' }

Write-Host '> Compilando o backend...'
Push-Location (Join-Path $Root 'backend'); mvn -q -B package -DskipTests; Pop-Location

Write-Host '> Backend (nova janela)'
$envCmd = @(
  "`$env:FIRESTORE_EMULATOR_HOST='localhost:8085'",
  "`$env:FIREBASE_AUTH_EMULATOR_HOST='localhost:9099'",
  "`$env:FIREBASE_STORAGE_EMULATOR_HOST='localhost:9199'",
  "`$env:SPRING_PROFILES_ACTIVE='dev'",
  "`$env:LUCENA_DATA_ENCRYPTION_KEY='$($Keys['LUCENA_DATA_ENCRYPTION_KEY'])'",
  "`$env:LUCENA_ADMIN_MFA_TOKEN_SECRET='$($Keys['LUCENA_ADMIN_MFA_TOKEN_SECRET'])'",
  "`$env:LUCENA_BOOTSTRAP_OWNER_EMAIL='$OwnerEmail'",
  "`$env:LUCENA_BOOTSTRAP_OWNER_CPF='$OwnerCpf'",
  "`$env:LUCENA_BOOTSTRAP_OWNER_NAME='Proprietario (dev)'",
  "cd '$Root\backend'",
  "java -jar target\lucena-drive-backend-0.1.0-SNAPSHOT.jar"
) -join '; '
Start-Process $Shell -ArgumentList '-NoExit', '-Command', $envCmd
Wait-Url 'http://127.0.0.1:8080/actuator/health'

Write-Host '> Portais Angular (3 novas janelas)'
foreach ($app in 'passenger', 'driver', 'admin') {
  Start-Process $Shell -ArgumentList '-NoExit', '-Command', "cd '$Root\frontend'; npx ng serve $app --host 127.0.0.1"
}
Wait-Url 'http://127.0.0.1:4200'
Wait-Url 'http://127.0.0.1:4201'
Wait-Url 'http://127.0.0.1:4202'

Write-Host @"

Lucena Drive em execução (dados FICTÍCIOS, Firebase Emulator):
  Passageiro   http://127.0.0.1:4200
  Motorista    http://127.0.0.1:4201
  Admin        http://127.0.0.1:4202   (login: $OwnerEmail / $OwnerPassword + app autenticador)
  API / docs   http://127.0.0.1:8080/swagger-ui.html
  Emulator UI  http://127.0.0.1:4000   (links de confirmação de e-mail na aba Auth)
Para encerrar, feche as janelas abertas.
"@
Start-Process 'http://127.0.0.1:4200'
