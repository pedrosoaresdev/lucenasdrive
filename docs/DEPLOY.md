# Implantação

> Não implante em produção antes de concluir [SECURITY_CHECKLIST.md](SECURITY_CHECKLIST.md), contratar o
> provedor de pagamentos e obter revisão jurídica (transporte remunerado é regulado por município).

## Ambientes
| Ambiente | Firebase | Pagamentos | Maps | Observação |
|---|---|---|---|---|
| dev | Emulator Suite (`demo-lucena-drive`) | `none` | opcional; fallback linha reta | dados fictícios |
| homologação | projeto Firebase separado | sandbox do provedor | chaves de homologação | dados fictícios |
| produção | projeto Firebase próprio | produção do provedor | chaves restritas | `MAPS_ALLOW_STRAIGHT_LINE_FALLBACK=false` |

Nunca compartilhe projeto Firebase, chaves ou segredos entre ambientes.

## Backend (sugestão: Google Cloud Run)
1. Conta de serviço dedicada com papéis mínimos: `roles/datastore.user`, `roles/firebaseauth.admin`,
   `roles/storage.objectViewer` (+ `iam.serviceAccountTokenCreator` em si mesma para URLs assinadas),
   `roles/firebasecloudmessaging.admin`.
2. Segredos no **Secret Manager** (montados como variáveis): `LUCENA_DATA_ENCRYPTION_KEY`,
   `LUCENA_ADMIN_MFA_TOKEN_SECRET` (32 bytes aleatórios, base64), `MAPS_SERVER_API_KEY`, `PAYMENTS_API_KEY`,
   `PAYMENTS_WEBHOOK_SECRET`. A chave de criptografia de campos **não pode ser perdida** (dados ficam ilegíveis);
   planeje rotação com recriptografia.
3. Variáveis: `FIREBASE_PROJECT_ID`, `FIREBASE_STORAGE_BUCKET`, `CORS_ALLOWED_ORIGINS` (domínios dos portais),
   `FORWARD_HEADERS_STRATEGY=native` atrás do balanceador, `SWAGGER_ENABLED=false`.
4. Imagem: `backend/Dockerfile` (usuário não-root, healthcheck em `/actuator/health/liveness`).
5. **Instância única de agendadores** ou lock distribuído: os jobs (`DispatchService.tick`, expiração de cupons,
   manutenção do chat, vencimento de CNH) são seguros por transação, mas executam em cada instância. Para várias
   instâncias, adicione ShedLock/lock no Firestore ou mova os jobs para Cloud Scheduler + endpoint interno.
6. **Rate limit** atual é em memória (por instância). Com várias instâncias, use Redis/Memorystore ou Cloud Armor.
7. Provisionamento do OWNER: execute uma vez com `LUCENA_BOOTSTRAP_OWNER_EMAIL/CPF/NAME`, confirme no log e
   **remova as variáveis**.

## Firebase
```bash
cd firebase
npx firebase deploy --only firestore:rules,firestore:indexes,storage --project <projeto>
```
Ative o **App Check** (reCAPTCHA Enterprise na web) para Firestore e Storage e a política de senha do Firebase
Auth (mín. 10 caracteres, letras e números), proteção contra enumeração de e-mail e SMS só se for usar verificação
de telefone.

## Portais (Firebase Hosting, Cloud Storage + CDN ou Nginx)
1. Substitua os marcadores `__FIREBASE_*__`, `apiBaseUrl` e `mapsBrowserKey` em
   `projects/*/src/environments/environment.ts` no pipeline (não são segredos, mas variam por ambiente).
2. `npx ng build passenger && npx ng build driver && npx ng build admin`.
3. Publique cada portal em seu domínio (ex.: `app.`, `motorista.`, `admin.`), HTTPS obrigatório, cabeçalhos de
   segurança (ver `frontend/docker/common.inc`) e CSP restritiva incluindo `maps.googleapis.com`,
   `*.googleapis.com`, `*.gstatic.com`, `fonts.googleapis.com`, `fonts.gstatic.com`.
4. O portal admin pode ser restringido por IP/IAP/Cloud Armor.

## CI/CD
`.github/workflows/ci.yml`: testes do backend (`mvn verify`), regras no Emulator, testes e build dos portais e
roteiro E2E. Para CD, adicione jobs por ambiente com aprovação manual para produção, implantação de regras
antes do backend e verificação pós-deploy (`/actuator/health`).

## Logs e monitoramento
- Logs estruturados (JSON Logstash) com `traceId` por requisição (`X-Request-Id`), já habilitados fora do perfil dev.
- Métricas via Actuator/Micrometer (`/actuator/metrics`, `/actuator/prometheus` se o registro Prometheus for
  adicionado). Restrinja `/actuator/**` exceto health na borda.
- Alertas recomendados: taxa de 5xx, latência p95, falhas de webhook (`webhookEvents.status = FAILED`), saques
  em PROCESSING > 24 h, corridas em SEARCHING > 2 min (já exibidos no dashboard), erros de despacho
  (`dispatch_tick_failed`).
- Backups: exportação agendada do Firestore (`gcloud firestore export`) e retenção conforme política.
