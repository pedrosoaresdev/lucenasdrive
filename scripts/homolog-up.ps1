# Homologação do Lucena Drive no projeto Firebase REAL "lucenasdrive" (dados de TESTE).
# Backend e portais rodam no seu computador; Auth, Firestore e Storage são os do projeto lucenasdrive.
# Leia antes: docs/HOMOLOGACAO.md
# Uso (na pasta do projeto):  Set-ExecutionPolicy -Scope Process Bypass; .\scripts\homolog-up.ps1
param(
  [string]$ServiceAccount = '',
  [switch]$DeployRules
)
$ErrorActionPreference = 'Stop'
$Shell = (Get-Process -Id $PID).Path
$Root = Split-Path -Parent $PSScriptRoot
$State = Join-Path $Root '.homolog'
New-Item -ItemType Directory -Force -Path $State | Out-Null
$ProjectId = 'lucenasdrive'

function Test-Command($name, $hint) {
  if (-not (Get-Command $name -ErrorAction SilentlyContinue)) { throw "Comando '$name' nao encontrado. $hint" }
}
Test-Command java 'Instale o Java 21+.'
Test-Command mvn 'Instale o Maven 3.9+.'
Test-Command npm 'Instale o Node.js 24 LTS.'

function Wait-Url($url) {
  for ($i = 0; $i -lt 240; $i++) {
    try { Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 2 | Out-Null; return } catch { Start-Sleep -Seconds 1 }
  }
  throw "Tempo esgotado aguardando $url"
}

# 1) Configuracao web publica do Firebase (frontend/projects/homolog.firebase.ts)
$cfgFile = Join-Path $Root 'frontend\projects\homolog.firebase.ts'
$cfg = Get-Content -Raw $cfgFile
if ($cfg -match 'COLE_AQUI') {
  throw "Preencha frontend\projects\homolog.firebase.ts com o firebaseConfig do app Web (Console Firebase > Configuracoes do projeto > Seus apps)."
}
$ApiKey = [regex]::Match($cfg, "apiKey:\s*'([^']+)'").Groups[1].Value
$Bucket = [regex]::Match($cfg, "storageBucket:\s*'([^']+)'").Groups[1].Value

# 2) Conta de servico do backend: arquivo FORA da pasta do projeto (nunca no git)
$SaFile = Join-Path $State 'service-account-path.txt'
if (-not $ServiceAccount -and (Test-Path $SaFile)) { $ServiceAccount = (Get-Content $SaFile -Raw).Trim() }
if (-not $ServiceAccount) {
  Write-Host 'Caminho do JSON da conta de servico (Console Firebase > Configuracoes > Contas de servico > Gerar nova chave).'
  Write-Host 'Guarde-o FORA da pasta do projeto, por exemplo C:\chaves\lucenasdrive-servico.json'
  $ServiceAccount = Read-Host 'Caminho do arquivo'
}
$ServiceAccount = $ServiceAccount.Trim('"', ' ')
if (-not (Test-Path $ServiceAccount)) { throw "Arquivo nao encontrado: $ServiceAccount" }
$saFull = (Resolve-Path $ServiceAccount).Path
if ($saFull.StartsWith($Root, [System.StringComparison]::OrdinalIgnoreCase)) {
  throw 'O JSON da conta de servico esta dentro da pasta do projeto. Mova-o para fora (ex.: C:\chaves\) para nao ir ao GitHub.'
}
Set-Content -Encoding ASCII $SaFile $saFull

# 3) Chaves do backend (criptografia de CPF e tokens de MFA) geradas uma vez e mantidas FORA do git.
#    Se forem perdidas, CPFs cifrados e autenticadores cadastrados deixam de ser legiveis.
$KeysFile = Join-Path $State 'keys.env'
if (-not (Test-Path $KeysFile)) {
  $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
  $k1 = New-Object byte[] 32; $rng.GetBytes($k1)
  $k2 = New-Object byte[] 32; $rng.GetBytes($k2)
  "LUCENA_DATA_ENCRYPTION_KEY=$([Convert]::ToBase64String($k1))`nLUCENA_ADMIN_MFA_TOKEN_SECRET=$([Convert]::ToBase64String($k2))" |
    Set-Content -Encoding ASCII $KeysFile
  Write-Host "Chaves criadas em $KeysFile - faca uma copia de seguranca desse arquivo."
}
$Keys = @{}
Get-Content $KeysFile | ForEach-Object { $p = $_.Split('=', 2); if ($p.Length -eq 2) { $Keys[$p[0]] = $p[1] } }

Write-Host '> Instalando dependencias (se necessario)...'
if (-not (Test-Path (Join-Path $Root 'firebase\node_modules'))) { npm ci --prefix (Join-Path $Root 'firebase') }
if (-not (Test-Path (Join-Path $Root 'frontend\node_modules'))) { npm ci --prefix (Join-Path $Root 'frontend') }

# 4) Regras e indices (uma vez, ou quando mudarem). Usa o SEU login do Firebase CLI.
if ($DeployRules -or -not (Test-Path (Join-Path $State 'rules-deployed'))) {
  Write-Host '> Publicando regras do Firestore/Storage e indices no projeto lucenasdrive (abre o login do Firebase se preciso)'
  Push-Location (Join-Path $Root 'firebase')
  npx firebase login
  npx firebase deploy --only 'firestore:rules,firestore:indexes,storage' --project $ProjectId
  if ($LASTEXITCODE -ne 0) { Pop-Location; throw 'Falha ao publicar regras. Confira se Firestore e Storage estao criados no Console.' }
  Pop-Location
  New-Item -ItemType File -Force -Path (Join-Path $State 'rules-deployed') | Out-Null
}

# 5) Proprietario (OWNER): somente na primeira vez. CPF e senha NAO sao gravados em disco.
$OwnerDone = Join-Path $State 'owner-done'
$bootstrap = @()
if (-not (Test-Path $OwnerDone)) {
  Write-Host '> Conta do proprietario (acesso ao painel administrativo)'
  $OwnerEmail = (Read-Host 'E-mail do proprietario').Trim().ToLower()
  $OwnerName = Read-Host 'Nome completo'
  $OwnerCpf = Read-Host 'CPF (somente para o cadastro; fica criptografado no banco)'
  $sec = Read-Host 'Senha (min. 10 caracteres, letras e numeros)' -AsSecureString
  $OwnerPassword = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($sec))
  $auth = "https://identitytoolkit.googleapis.com/v1/accounts"
  try {
    $r = Invoke-RestMethod -Method Post -ContentType 'application/json' -Uri "$($auth):signUp?key=$ApiKey" `
      -Body (@{ email = $OwnerEmail; password = $OwnerPassword; returnSecureToken = $true } | ConvertTo-Json)
    Invoke-RestMethod -Method Post -ContentType 'application/json' -Uri "$($auth):sendOobCode?key=$ApiKey" `
      -Body (@{ requestType = 'VERIFY_EMAIL'; idToken = $r.idToken } | ConvertTo-Json) | Out-Null
    Write-Host "  Conta criada. Confirme o e-mail pelo link enviado para $OwnerEmail antes de entrar no painel."
  } catch {
    Write-Host '  A conta ja existia (ou o login por e-mail/senha nao esta ativado no Authentication). Seguindo.'
  }
  $OwnerPassword = $null
  $bootstrap = @(
    "`$env:LUCENA_BOOTSTRAP_OWNER_EMAIL='$OwnerEmail'",
    "`$env:LUCENA_BOOTSTRAP_OWNER_CPF='$OwnerCpf'",
    "`$env:LUCENA_BOOTSTRAP_OWNER_NAME='$($OwnerName -replace "'", "''")'"
  )
}

Write-Host '> Compilando o backend...'
Push-Location (Join-Path $Root 'backend'); mvn -q -B package -DskipTests; Pop-Location

Write-Host '> Backend ligado ao Firebase lucenasdrive (nova janela)'
$origins = 'http://localhost:4200,http://localhost:4201,http://localhost:4202,http://127.0.0.1:4200,http://127.0.0.1:4201,http://127.0.0.1:4202'
$envCmd = @(
  "`$env:FIREBASE_PROJECT_ID='$ProjectId'",
  "`$env:FIREBASE_STORAGE_BUCKET='$Bucket'",
  "`$env:FIREBASE_USE_EMULATORS='false'",
  "`$env:GOOGLE_APPLICATION_CREDENTIALS='$saFull'",
  "`$env:LUCENA_DATA_ENCRYPTION_KEY='$($Keys['LUCENA_DATA_ENCRYPTION_KEY'])'",
  "`$env:LUCENA_ADMIN_MFA_TOKEN_SECRET='$($Keys['LUCENA_ADMIN_MFA_TOKEN_SECRET'])'",
  "`$env:CORS_ALLOWED_ORIGINS='$origins'",
  "`$env:MAPS_ALLOW_STRAIGHT_LINE_FALLBACK='true'",
  "`$env:PAYMENTS_ENVIRONMENT='sandbox'"
) + $bootstrap + @(
  "cd '$Root\backend'",
  "java -jar target\lucena-drive-backend-0.1.0-SNAPSHOT.jar"
)
Start-Process $Shell -ArgumentList '-NoExit', '-Command', ($envCmd -join '; ')
Wait-Url 'http://127.0.0.1:8080/actuator/health'
if ($bootstrap.Count -gt 0) { New-Item -ItemType File -Force -Path $OwnerDone | Out-Null }

Write-Host '> Portais Angular em modo homologacao (3 novas janelas)'
foreach ($app in 'passenger', 'driver', 'admin') {
  Start-Process $Shell -ArgumentList '-NoExit', '-Command', "cd '$Root\frontend'; npx ng serve $app -c homolog --host 127.0.0.1"
}
Wait-Url 'http://127.0.0.1:4200'
Wait-Url 'http://127.0.0.1:4201'
Wait-Url 'http://127.0.0.1:4202'

Write-Host @"

Lucena Drive em HOMOLOGACAO (Firebase lucenasdrive). Use apenas dados de teste.
  Passageiro   http://127.0.0.1:4200
  Motorista    http://127.0.0.1:4201
  Admin        http://127.0.0.1:4202   (e-mail do proprietario + app autenticador)
Para encerrar, feche as janelas abertas.
"@
Start-Process 'http://127.0.0.1:4202'
