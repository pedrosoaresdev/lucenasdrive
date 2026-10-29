# API REST — visão por módulo

Especificação completa (gerada pelo springdoc): [`docs/openapi.json`](openapi.json) · em execução:
`/v3/api-docs` e `/swagger-ui.html` (Swagger habilitado no perfil `dev`; desabilitado por padrão).

**Autenticação:** `Authorization: Bearer <ID token do Firebase>`. Rotas `/api/v1/admin/**` exigem papel de
equipe **e** `X-Lucena-MFA: <token de sessão>` (obtido após TOTP). Operações críticas exigem MFA confirmado
há ≤ 5 min (`POST /admin/auth/step-up`). Erros seguem `ApiError { code, message, status, path, timestamp,
traceId, fieldErrors[], details }`; o frontend traduz pelo `code`.

**Idempotência:** `POST /rides`, saques e ajustes exigem o cabeçalho `Idempotency-Key` (8–64 caracteres).

## Público
| Método e rota | Descrição |
|---|---|
| GET `/api/v1/public/config` | Estado das integrações (rotas, pagamentos), métodos de pagamento disponíveis, categorias, cidades ativas, versões dos termos |
| POST `/api/v1/webhooks/payments/{provider}` | Webhook do provedor (autenticado por assinatura `X-Lucena-Signature`) |

## Conta (qualquer usuário autenticado)
| Rota | Descrição |
|---|---|
| GET/PATCH `/api/v1/me` | Perfil; altera nome, telefone, foto |
| PUT `/api/v1/me/notification-preferences` | Preferências |
| POST/DELETE `/api/v1/me/push-tokens` | Registro de token FCM |
| POST `/api/v1/me/sessions/revoke-all` | Encerra sessões em todos os dispositivos |
| GET/POST `/api/v1/me/privacy-requests` | Solicitações LGPD |
| GET `/api/v1/me/data-export` | Cópia dos próprios dados em JSON (login recente; 3/hora; auditado) |
| POST `/api/v1/notifications/{id}/read` | Marca notificação como lida |
| GET/PUT `/api/v1/me/location-sharing` | Consentimento opcional de localização para a equipe |
| POST `/api/v1/me/presence` | Envia a posição atual (`app`: PASSENGER/DRIVER); exige consentimento; 1 a cada 10 s |

## Passageiro (PASSENGER)
| Rota | Descrição |
|---|---|
| POST `/api/v1/passengers/register` | Cadastro (CPF validado, idade ≥ 18, unicidade) |
| GET `/api/v1/passengers/me` · PUT `/me/favorites` | Perfil, favoritos (Casa/Trabalho/outros) |
| POST `/api/v1/rides/estimate` | Cotação por categoria (rota, preço do servidor, cupom, motoristas próximos) |
| POST `/api/v1/rides` | Solicita corrida a partir da cotação (`Idempotency-Key`) |
| GET `/api/v1/rides/active` · `/pending-rating` · `/{id}` · `/?as=passenger` | Corrida ativa, avaliação pendente, detalhe, histórico paginado |
| POST `/api/v1/rides/{id}/cancel` | Cancelamento (taxa após chegada ou tolerância de 2 min) |
| POST/DELETE `/api/v1/rides/{id}/share` | Cria/desativa link de acompanhamento (token só no link; expira 1 h após o fim) |
| GET `/api/v1/public/ride-shares/{token}` | Visão pública mínima da viagem (sem login; limitada por IP) |
| POST `/api/v1/rides/{id}/safety-alert` | Alerta de segurança (passageiro ou motorista) → chamado URGENTE com última posição |
| POST `/api/v1/rides/{id}/rating` · `/rating/skip` | Avaliação 1–5 estrelas / pular |
| GET `/api/v1/drivers/{id}/ratings/summary` | Média, distribuição e comentários visíveis do motorista |
| GET `/api/v1/passengers/me/rating` | Média recebida dos motoristas (sem notas individuais) |

## Motorista (DRIVER)
| Rota | Descrição |
|---|---|
| POST `/api/v1/drivers/register` | Cadastro (idade ≥ 21, CNH com EAR, cidade) |
| GET `/api/v1/drivers/me` | Situação, documentos, veículos, pendências |
| POST `/me/vehicles` · `/me/vehicles/{id}/activate` · `/me/documents` · `/me/submit` | Veículos, documentos (Storage privado), envio para análise |
| POST `/me/online` · `/me/offline` | Disponibilidade (exige aprovação, veículo aprovado, CNH válida) |
| GET `/api/v1/driver/offers` · POST `/offers/{rideId}/accept` · `/decline` | Ofertas e aceite transacional |
| POST `/api/v1/driver/rides/{id}/status` · `/cancel` | Etapas da corrida (máquina de estados); `DRIVER_ARRIVED` exige posição recente (≤ 2 min) a até 500 m do embarque (`TOO_FAR_FROM_PICKUP`) |
| POST `/api/v1/driver/rides/{id}/cancel` | Desistência: antes do embarque a corrida volta à busca (até 2 vezes), depois é cancelada |
| POST `/api/v1/driver/rides/{id}/passenger-rating` | Avaliação recíproca do passageiro (1–5, uma vez, mesmo prazo) |
| POST `/api/v1/driver/location` | Localização (limitada a 1/2 s) |
| GET `/api/v1/drivers/me/finance` · PUT `/pix-key` · GET/POST `/payouts` | Ganhos, saldos por estado, chave Pix, saques (login recente) |
| GET `/api/v1/drivers/me/ratings` · POST `/{id}/dispute` | Avaliações recebidas e contestação |

## Chat (participantes da corrida)
| Rota | Descrição |
|---|---|
| POST `/api/v1/rides/{id}/chat/messages` | Envia (`clientMessageId` para deduplicar) |
| GET `/api/v1/rides/{id}/chat/messages?before=` | Histórico paginado |
| POST `/api/v1/rides/{id}/chat/read` | Confirmação de leitura |

## Suporte (autenticado)
`GET/POST /api/v1/support/tickets`, `GET /{id}`, `GET/POST /{id}/messages`. Anexos: o app envia ao Storage em
`supportAttachments/{uid}/` e informa `attachmentPaths`; o servidor confere dono, existência, tipo (PDF/JPG/PNG/WEBP)
e tamanho (≤ 10 MB). A equipe baixa por `GET /api/v1/admin/support/tickets/{id}/attachments/{n}` (auditado).

Tarifas: `PUT /api/v1/admin/cities/{cityId}/fares/{categoria}` aceita `effectiveFrom` (10 min a 180 dias à frente) para
programar a alteração; `POST …/cancel-scheduled` cancela. Conversas arquivadas em redespachos:
`GET /api/v1/admin/rides/{id}/chat-archives?reason=` (auditado).

"O olho de Deus" (ADMIN/OWNER): `GET /api/v1/admin/god-eye/positions?kind=` — posições dos últimos 5 min de quem
consentiu (nome, tipo, lat/lng, precisão, horário); auditado.

Anonimização LGPD (ADMIN/OWNER + MFA recente): `POST /api/v1/admin/privacy-requests/{id}/anonymize`.

## Administração (equipe + MFA) — papéis por rota
| Área | Rotas | Papéis |
|---|---|---|
| MFA | `/admin/auth/status`, `/totp/enroll`, `/totp/confirm`, `/verify`, `/step-up`, `/recovery-codes`, `/sessions`, `/sessions/{id}/revoke`, `/logout` | equipe (MFA ainda não exigido para cadastrar/verificar) |
| Dashboard | `/admin/dashboard/summary`, `/timeseries`, `/alerts` | equipe |
| Usuários | `GET /admin/users`, `/users/{uid}` · `POST /users/{uid}/status` · `POST /users/{uid}/pii` | SUPPORT/ADMIN/OWNER · ADMIN/OWNER |
| Equipe | `GET /admin/staff` · `POST /admin/staff/roles` | ADMIN (só SUPPORT) / OWNER (exceto OWNER) |
| LGPD | `GET/POST /admin/privacy-requests` | ADMIN/OWNER |
| Motoristas | `/admin/drivers`, `/{id}`, `/{id}/ratings`, `/{id}/finance`, `/{id}/status`, `/{id}/documents/review`, `/{id}/documents/url`, `/vehicles/{id}/status`, `/{id}/warnings` | leitura SUPPORT+; decisões ADMIN/OWNER; finanças FINANCE+ |
| Corridas | `/admin/rides`, `/live`, `/{id}`, `/{id}/chat?reason=`, `/{id}/cancel`, `/{id}/adjustments` | equipe; chat/cancelar SUPPORT+; ajustes FINANCE/ADMIN/OWNER + step-up |
| Geografia | `/admin/cities`, `/cities/{id}`, `/cities/{id}/areas`, `/areas`, `/areas/{id}` | leitura equipe; escrita ADMIN/OWNER |
| Tarifas | `/admin/cities/{id}/fares`, `PUT /cities/{id}/fares/{categoria}`, `/fares/{configId}/history` | escrita ADMIN/OWNER + step-up |
| Cupons | `/admin/coupons`, `/{code}`, `/{code}/active`, `/{code}/redemptions`, `/{code}/results` | leitura FINANCE+; escrita ADMIN/OWNER |
| Avaliações | `/admin/ratings`, `/stats`, `/{id}/moderation` | SUPPORT/ADMIN/OWNER |
| Financeiro | `/admin/finance/summary`, `/statement`, `/statement.csv`, `/reconciliation`, `/payouts`, `/pix-key`, `/withdrawals` | FINANCE/ADMIN/OWNER; CSV FINANCE/OWNER; chave Pix OWNER; saque FINANCE/OWNER + step-up |
| Suporte | `/admin/support/tickets`, `/{id}`, `/{id}/messages` | SUPPORT/ADMIN/OWNER |
| Auditoria | `/admin/audit`, `/admin/security-events` | ADMIN/OWNER |
