#!/usr/bin/env bash
# Sobe o ambiente local SEM Docker: Firebase Emulator Suite, backend (perfil dev) e os 3 portais.
# Requisitos: Java 21, Maven 3.9+, Node 22.22.3+ ou 24.15+ (Angular 22). Ctrl+C encerra tudo.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOGS="$ROOT/.dev-logs"; mkdir -p "$LOGS"
OWNER_EMAIL="${OWNER_EMAIL:-dono@lucena.test}"
OWNER_PASSWORD="${OWNER_PASSWORD:-SenhaDono2026x}"
OWNER_CPF="${OWNER_CPF:-390.533.447-05}"   # CPF fictício, válido apenas matematicamente

# Chaves de DESENVOLVIMENTO persistidas localmente (fora do git) para que reinícios do backend não tornem
# ilegíveis os dados criptografados no Emulator. Nunca use estas chaves fora do ambiente local.
KEYS="$LOGS/dev-keys.env"
if [ ! -f "$KEYS" ]; then
  echo "LUCENA_DATA_ENCRYPTION_KEY=$(openssl rand -base64 32)" > "$KEYS"
  echo "LUCENA_ADMIN_MFA_TOKEN_SECRET=$(openssl rand -base64 32)" >> "$KEYS"
fi
set -a; . "$KEYS"; set +a

pids=()
cleanup() { echo; echo "Encerrando..."; kill "${pids[@]}" 2>/dev/null || true; }
trap cleanup EXIT INT TERM

wait_for() { for _ in $(seq 1 120); do curl -s "$1" >/dev/null 2>&1 && return 0; sleep 1; done; echo "Timeout: $1"; exit 1; }

echo "▶ Firebase Emulator Suite"
(cd "$ROOT/firebase" && [ -d node_modules ] || npm ci --prefix "$ROOT/firebase")
(cd "$ROOT/firebase" && npx firebase emulators:start --project demo-lucena-drive > "$LOGS/emulators.log" 2>&1) & pids+=($!)
wait_for http://127.0.0.1:9099 && wait_for http://127.0.0.1:8085

echo "▶ Conta do proprietário no Auth Emulator ($OWNER_EMAIL)"
curl -s -X POST "http://127.0.0.1:9099/identitytoolkit.googleapis.com/v1/accounts:signUp?key=demo-api-key" \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"$OWNER_EMAIL\",\"password\":\"$OWNER_PASSWORD\"}" > /dev/null

echo "▶ Backend (perfil dev)"
(cd "$ROOT/backend" && mvn -q -B package -DskipTests && \
  FIRESTORE_EMULATOR_HOST=localhost:8085 FIREBASE_AUTH_EMULATOR_HOST=localhost:9099 \
  FIREBASE_STORAGE_EMULATOR_HOST=localhost:9199 SPRING_PROFILES_ACTIVE=dev \
  LUCENA_BOOTSTRAP_OWNER_EMAIL="$OWNER_EMAIL" LUCENA_BOOTSTRAP_OWNER_CPF="$OWNER_CPF" \
  LUCENA_BOOTSTRAP_OWNER_NAME="Proprietário (dev)" \
  java -jar target/lucena-drive-backend-0.1.0-SNAPSHOT.jar > "$LOGS/backend.log" 2>&1) & pids+=($!)
wait_for http://127.0.0.1:8080/actuator/health

echo "▶ Portais Angular"
(cd "$ROOT/frontend" && [ -d node_modules ] || npm ci --prefix "$ROOT/frontend")
for app in passenger driver admin; do
  (cd "$ROOT/frontend" && npx ng serve "$app" --host 127.0.0.1 > "$LOGS/$app.log" 2>&1) & pids+=($!)
done
wait_for http://127.0.0.1:4200 && wait_for http://127.0.0.1:4201 && wait_for http://127.0.0.1:4202

cat <<MSG

Lucena Drive em execução (dados FICTÍCIOS, Firebase Emulator):
  Passageiro   http://127.0.0.1:4200
  Motorista    http://127.0.0.1:4201
  Admin        http://127.0.0.1:4202   (login: $OWNER_EMAIL / $OWNER_PASSWORD + TOTP)
  API / docs   http://127.0.0.1:8080/swagger-ui.html
  Emulator UI  http://127.0.0.1:4000   (links de verificação de e-mail ficam em Auth)
Logs em $LOGS. Teste de aceitação: node e2e/acceptance.cjs
MSG
wait
