# Instalação e execução local

## Requisitos
| Ferramenta | Versão |
|---|---|
| Java (JDK) | 21 |
| Maven | 3.9+ |
| Node.js | 24 LTS (mínimo 22.22.3 — exigência do Angular CLI 22) |
| Java 17+ no PATH | necessário para o Firebase Emulator |
| (opcional) Docker + Compose | para `docker-compose.yml` |

## Opção A — script único (recomendado)
Linux/macOS (ou Git Bash/WSL no Windows):
```bash
./scripts/dev-up.sh
```
Windows (PowerShell, na pasta do projeto — não use `C:\Windows\System32`):
```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\scripts\dev-up.ps1
```
O script instala dependências (se faltarem), inicia o **Firebase Emulator Suite** (Auth 9099, Firestore 8085,
Storage 9199, UI 4000), cria a conta fictícia do proprietário no Auth Emulator, inicia o backend no perfil `dev`
com o **provisionamento do OWNER** e serve os três portais. Logs em `.dev-logs/`.

Primeiro acesso ao admin (http://127.0.0.1:4202): `dono@lucena.test` / `SenhaDono2026x` → cadastre o TOTP no
aplicativo autenticador (Google Authenticator, Microsoft Authenticator, 1Password…) → guarde os códigos de
recuperação.

Fluxo mínimo para testar: Admin › Gestão geográfica (cidade + área de cobertura por vértices) › Tarifas
(configurar Essencial) → Motorista: cadastro, veículo, documentos, enviar → Admin: aprovar documentos, veículo e
motorista → Motorista: ficar online → Passageiro: cadastro, confirmar e-mail (link na UI do Emulator, aba Auth),
informar origem/destino por coordenadas (ex.: `-7.1195, -34.8450`) → solicitar.

O roteiro `node e2e/acceptance.cjs` executa exatamente esse fluxo em navegador (requer Playwright:
`npm i -g playwright && npx playwright install chromium`; use `NODE_PATH=$(npm root -g)`).

## Opção B — passo a passo
```bash
# 1. Emuladores
cd firebase && npm ci && npx firebase emulators:start --project demo-lucena-drive
# 2. Conta do proprietário (fictícia)
curl -X POST "http://127.0.0.1:9099/identitytoolkit.googleapis.com/v1/accounts:signUp?key=demo-api-key" \
  -H 'Content-Type: application/json' -d '{"email":"dono@lucena.test","password":"SenhaDono2026x"}'
# 3. Backend
cd backend && mvn package -DskipTests
FIRESTORE_EMULATOR_HOST=localhost:8085 FIREBASE_AUTH_EMULATOR_HOST=localhost:9099 \
FIREBASE_STORAGE_EMULATOR_HOST=localhost:9199 SPRING_PROFILES_ACTIVE=dev \
LUCENA_BOOTSTRAP_OWNER_EMAIL=dono@lucena.test LUCENA_BOOTSTRAP_OWNER_CPF=390.533.447-05 \
java -jar target/lucena-drive-backend-0.1.0-SNAPSHOT.jar
# 4. Portais
cd frontend && npm ci && npx ng serve passenger   # 4200 (também: driver 4201, admin 4202)
```

## Opção C — Docker Compose
```bash
cp .env.example .env    # ajuste se necessário
docker compose up --build
```
> Os Dockerfiles e o compose **não puderam ser verificados** no ambiente em que foram escritos (sem daemon
> Docker). Valide antes de depender deles. O provisionamento do OWNER exige a conta criada no Auth Emulator
> antes de iniciar o backend (veja o script da opção A).

## Mapas no ambiente local
Sem chave do Google, os portais mostram "Mapa indisponível · integração pendente" e aceitam coordenadas; a
cotação usa estimativa em linha reta **sinalizada**. Para mapas reais, crie uma chave de navegador restrita a
`http://localhost:*`/`http://127.0.0.1:*` e às APIs Maps JavaScript, Places (New) e Geocoding, e informe em
`frontend/projects/*/src/environments/environment.development.ts` (`mapsBrowserKey`). Para rotas reais, defina
`MAPS_SERVER_API_KEY` no backend (Routes API). Ver [INTEGRATIONS.md](INTEGRATIONS.md).

## Testes
```bash
cd backend && mvn verify
cd firebase && npm run test:rules
cd frontend && npx ng test shared --watch=false && npx ng build passenger && npx ng build driver && npx ng build admin
```
