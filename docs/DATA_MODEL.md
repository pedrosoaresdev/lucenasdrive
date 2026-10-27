# Modelo de dados (Cloud Firestore)

Convenções:
- **Gravação:** em todas as coleções, somente o backend (Admin SDK). Regras do cliente: `allow write: if false`.
- **Instantes** gravados como epoch em milissegundos (`number`) — ordenáveis e consultáveis por intervalo.
- **Dinheiro** em centavos (`number` inteiro); percentuais/multiplicadores em pontos-base (10 000 = 100 % / 1,0×).
- **Leitura pelo cliente:** somente onde indicado; o resto é negado (`match /{document=**}`).
- Índices compostos: [`firebase/firestore.indexes.json`](../firebase/firestore.indexes.json).
- Classes Java correspondentes ficam no pacote de cada módulo (ex.: `ride/Ride.java`).

## Identidade e perfis

### `users/{uid}` — perfil básico e papéis
| Campo | Tipo | Observação |
|---|---|---|
| id, email, fullName, phoneNumber, photoUrl | string | photoUrl = caminho no Storage (`profilePhotos/{uid}/…`) |
| roles | string[] | PASSENGER, DRIVER, SUPPORT, FINANCE, ADMIN, OWNER — **fonte de verdade da autorização** |
| status | string | ACTIVE, SUSPENDED, PENDING_DELETION, DELETED |
| cpfMasked | string | `***.456.789-**` (nunca o CPF completo) |
| notificationPreferences | map | rideUpdates, chatMessages, promotions, financial, push, hideContentOnLockScreen |
| termsVersion, privacyVersion, consentAt | string, string, number | registro de aceite |
| statusReason, createdAt, updatedAt | | |

Leitura: o próprio usuário. Retenção: enquanto a conta existir; exclusão via solicitação LGPD (anonimização).

### `userPrivate/{uid}` — dados pessoais sensíveis
`cpfEncrypted` (AES-256-GCM), `cpfHash` (HMAC), `birthDate` (AAAA-MM-DD), `createdAt`. **Sem leitura pelo
cliente.** Exibição a ADMIN/OWNER somente com justificativa, MFA recente e auditoria (`PII_ACCESSED`).

### `cpfIndex/{hmac(cpf)}` · `plateIndex/{hmac(placa)}`
Unicidade de CPF e placa sem expor o valor. `{ uid, createdAt }` / `{ vehicleId, driverId }`. Sem leitura.

### `passengerProfiles/{uid}`
`favorites[] {kind HOME|WORK|OTHER, label, place}`, `recentDestinations[]` (até 10), `completedRides`,
`cancelledRides`, `ratingSum`, `ratingCount` (avaliações recebidas dos motoristas; média calculada no servidor e
copiada para `rides.passenger.rating` na solicitação, exibida nas ofertas). Leitura: o próprio.

### `driverProfiles/{uid}`
| Campo | Observação |
|---|---|
| status | INCOMPLETE, PENDING_REVIEW, APPROVED, REJECTED, SUSPENDED, DOCUMENTS_EXPIRED |
| cnhNumberMasked, cnhCategory, cnhExpiresOn, cnhEar | número completo só em `driverPrivate` (criptografado) |
| documents[] | `{type, storagePath, contentType, sizeBytes, status, expiresOn, vehicleId, uploadedAt, reviewedBy, reviewedAt, reviewNote}` |
| cityId, activeVehicleId | |
| ratingSum, ratingCount, ratingAvg, starCounts{1..5} | mantidos na **mesma transação** da avaliação |
| completedRides, cancelledRides, warnings, statusReason, submittedAt, reviewedAt, reviewedBy | |

Leitura: o próprio. Índice: `status + updatedAt`.

### `driverPrivate/{uid}` — `cnhNumberEncrypted`. Sem leitura.

### `vehicles/{id}`
`driverId, make, model, year, color, plate (normalizada), category, status (PENDING_REVIEW|APPROVED|REJECTED|SUSPENDED), crlvExpiresOn, statusReason`. Leitura: o motorista dono.

### `driverLocations/{uid}` — posição operacional
`online, available (online e sem corrida), cityId, categories[], vehicleId, lat, lng, heading, speedMps, accuracyM, currentRideId, onlineSince, updatedAt`. **Sem leitura pelo cliente** (o passageiro recebe a posição
do seu motorista pelo documento da corrida). Sem dados pessoais. Índice: `available + cityId + categories (array-contains)`. Escala futura: geohash.

## Corridas

### `fareQuotes/{id}` — cotação calculada no servidor (5 min, uso único)
`passengerId, cityId, category, origin, destination, route, fare (FareBreakdown), multiplierBp, couponCode, couponMessage, expiresAt`. Sem leitura. Removida ao criar a corrida.

### `rides/{id}` — corrida (documento acompanhado em tempo real)
| Campo | Observação |
|---|---|
| code | código legível (`LD-7K3M9Q`) |
| passengerId, driverId, participantIds[] | `participantIds` usado pelas regras de leitura |
| status | máquina de estados (ver ARCHITECTURE.md) |
| category, cityId, origin{lat,lng,address,placeId}, destination | |
| route {distanceMeters, durationSeconds, encodedPolyline, source} | source: GOOGLE_ROUTES ou STRAIGHT_LINE_ESTIMATE (dev) |
| fare | `fareConfigId, fareConfigVersion, baseFareCents, distanceCents, timeCents, subtotalCents, multiplierBp, minimumApplied, totalCents, discountCents, finalCents, commissionBp, commissionCents, driverEarningCents, cancellationFeeCents` |
| couponCode, paymentMethod (CASH/PIX/CARD), paymentStatus | |
| passenger {userId, name (primeiro nome + inicial), photoPath} | snapshot de exibição |
| driver {userId, name, photoPath, ratingAvg, ratingCount, vehicle…, plate} | snapshot no aceite |
| driverPosition {lat, lng, heading, updatedAt}, etaSeconds | atualizados por escrita parcial |
| timeline {requestedAt, acceptedAt, arrivingAt, arrivedAt, startedAt, completedAt, cancelledAt} | |
| cancellation {byUserId, byRole, reasonCode, reasonText, feeCents} | |
| rating {status NOT_APPLICABLE/PENDING/SUBMITTED/SKIPPED, stars} | avaliação pendente sobrevive ao fechamento do app |
| driverRating {status} | avaliação recíproca (motorista → passageiro); **somente o estado** — a nota não é gravada aqui porque o passageiro lê este documento |

Leitura: `request.auth.uid in participantIds`. Sem CPF, telefone ou e-mail. Retenção: registros históricos são
mantidos mesmo com suspensão de contas (obrigações legais/fiscais); prazo a definir com o jurídico.

### `rideDispatch/{rideId}` — estado interno do despacho
`batches, offeredDriverIds[], searchStartedAt, lastBatchAt, finished`. Sem leitura (evita expor IDs de outros motoristas).

### `rideOffers/{rideId}_{driverId}`
`status (PENDING|ACCEPTED|DECLINED|EXPIRED|SUPERSEDED), category, pickupAddress, destinationAddress, pickupLat/Lng, distanceToPickupMeters, tripDistanceMeters, tripDurationSeconds, estimatedEarningCents, passengerName, expiresAt`. Leitura: o motorista destinatário (consulta com `driverId == uid`). Índices: `driverId+status`, `rideId+status`, `status+expiresAt`.

### `rideEvents/{id}` — trilha imutável
`rideId, type, fromStatus, toStatus, actorId, actorRole, data, at`. Sem leitura pelo cliente (equipe via API).

### `idempotencyKeys/{uid}_{key}` — `{ rideId, createdAt }`. Sem leitura.

## Chat

### `rideChats/{rideId}`
`passengerId, driverId, participantIds[], status (OPEN|READ_ONLY|CLOSED), createdAt, readOnlySince, closesAt, lastMessageAt, lastReadAt{uid: ms}`.
### `rideChats/{rideId}/messages/{id}`
`senderId, senderRole (PASSENGER|DRIVER|SYSTEM), type (TEXT|SYSTEM; extensível a IMAGE/LOCATION), text, systemEvent, clientMessageId, createdAt (servidor)`. ID: `{senderId}_{clientMessageId}` ou `sys_{evento}`.

Leitura: participante enquanto `status != CLOSED`. Escrita: só a API (participante validado no servidor, texto
sanitizado, até 500 caracteres, 10 mensagens/10 s). Retenção: OPEN durante a corrida → READ_ONLY por 7 dias →
CLOSED → mensagens excluídas após 180 dias (job `ChatService.maintenance`). Acesso da equipe: justificativa
obrigatória + auditoria `ADMIN_CHAT_ACCESS`.

## Avaliações

### `ratings/{rideId}_{raterRole}`
`raterId, raterRole, rateeId, rateeRole, stars (1–5), comment (≤ 500), tags[], status (VISIBLE|COMMENT_HIDDEN|INVALIDATED), reported, moderation[] {action, byUserId, reason, at}`. ID determinístico ⇒ uma avaliação por corrida e avaliador. Avaliação recíproca: `{rideId}_DRIVER` (rateeRole PASSENGER) atualiza `passengerProfiles.ratingSum/ratingCount`; a moderação ajusta o agregado do perfil correspondente. Sem leitura direta (resumo público via API, sem identificar avaliadores).

## Geografia e comercial

| Coleção | Campos principais | Leitura cliente |
|---|---|---|
| `cities/{slug-uf}` | name, state, ibgeCode, center, active, timezone | não (lista pública via `/public/config`) |
| `serviceAreas/{id}` | cityId, name, type (COVERAGE/PICKUP_ZONE/RESTRICTED/FARE_REGION), polygon[{lat,lng}], active, fareMultiplierBp | não |
| `fareConfigs/{cityId}_{CATEGORIA}` | tarifa vigente + version, effectiveFrom, updatedBy | não |
| `fareConfigVersions/{configId}_v{n}` | snapshot, previous, changedBy, changedAt, reason, effectiveFrom (imutável) | não |
| `coupons/{CODIGO}` | type, percentBp/amountCents, maxDiscountCents, minFareCents, startsAt/endsAt, maxRedemptions, maxPerUser, redemptionCount, budgetCents, budgetUsedCents, cityIds, categories, newPassengersOnly, eligibleUserIds, active | não |
| `couponRedemptions/{CODIGO}_{rideId}` | userId, rideId, discountCents, status (RESERVED/CONFIRMED/RELEASED) | não |
| `couponUsage/{CODIGO}_{uid}` | count (limite por usuário) | não |

Ruas e endereços **não** são cadastrados: a busca usa Google Places/Geocoding; só polígonos necessários às regras
ficam no banco. O backend valida que origem e destino estão na mesma cobertura ativa e fora de zonas restritas.

## Financeiro

| Coleção | Conteúdo |
|---|---|
| `paymentTransactions/{id}` | rideId, passengerId, driverId, method, amountCents, status (CASH_DECLARED, AWAITING_PROVIDER, CONFIRMED, FAILED, REFUNDED, CHARGEBACK…), provider, providerChargeId, providerFeeCents, statusHistory[] |
| `ledgerEntries/{hash(idempotencyKey)}` | type, account (`PLATFORM` / `DRIVER:{uid}`), direction (CREDIT/DEBIT), amountCents, currency, rideId, userId, provider, externalRef, status (RECORDED/AWAITING_SETTLEMENT/AVAILABLE/PAID/REVERSED), statusHistory[], idempotencyKey, reversalOf, description |
| `payoutRequests/{id}` | ownerId, ownerType (DRIVER/PLATFORM), amountCents, pixKeyMasked, status (REQUESTED…COMPLETED/FAILED), providerTransferId, receiptUrl, failureReason, history[] |
| `pixKeys/{ownerId}` | keyType, keyEncrypted, keyMasked, updatedBy |
| `webhookEvents/{provider}_{eventId}` | type, status (RECEIVED/PROCESSED/FAILED), error — idempotência e auditoria |

Lançamentos nunca são apagados; correções geram `ADJUSTMENT`/`REVERSAL`. Sem leitura pelo cliente.

## Plataforma, segurança e suporte

| Coleção | Conteúdo | Leitura cliente |
|---|---|---|
| `notifications/{id}` | userId, type, title, body, data, read, createdAt | destinatário |
| `pushTokens/{sha256(token)}` | userId, token, platform | não |
| `supportTickets/{id}` (+ `/messages`) | userId, userRole, category, priority, status, rideId, subject, description, attachmentPaths, assigneeId, history[] | não (via API) |
| `privacyRequests/{id}` | userId, type, status, details, resolutionNote, handledBy | não (via API) |
| `adminAuditLogs/{id}` | actorId, actorRoles, action, targetType, targetId, before, after, reason, ip, userAgent, at | não |
| `adminSecurity/{uid}` | totpSecretEncrypted, recoveryCodeHashes[], failedAttempts, lockedUntil, lastUsedTimeStep, knownDeviceHashes[] | não |
| `adminSessions/{id}` | uid, createdAt, expiresAt, mfaVerifiedAt, ip, userAgent, revoked | não |
| `securityEvents/{id}` | uid, type (MFA_SUCCESS/FAILURE/LOCKED, NEW_DEVICE, STEP_UP, SESSION_REVOKED…), ip, userAgent, at | não |
| `platformSettings` | reservado para parâmetros globais futuros | não |
| `schedulerLocks/{tarefa}` | owner (instância), until — lease das tarefas agendadas (despacho 30 s; cupons, chat e documentos 10 min) | não |

## Ajustes em relação à lista solicitada (justificativas)
- `driverLocations` sem leitura pelo cliente: posição do motorista chega ao passageiro pela corrida (menos dados expostos).
- `fareQuotes`, `rideOffers`, `rideDispatch`, `idempotencyKeys`, `couponUsage`, `cpfIndex`, `plateIndex`, `userPrivate`, `driverPrivate`, `pixKeys`, `webhookEvents`, `adminSecurity`, `adminSessions`, `securityEvents`, `pushTokens`, `privacyRequests` foram adicionadas para preço no servidor, idempotência, unicidade sem expor dados, segregação de dados sensíveis e segurança administrativa.

## Storage
| Caminho | Escrita | Leitura |
|---|---|---|
| `driverDocuments/{uid}/{arquivo}` | dono; PDF/JPEG/PNG/WebP < 10 MB | ninguém pelo cliente; equipe por URL assinada (5 min) emitida e auditada pelo backend |
| `profilePhotos/{uid}/{arquivo}` | dono; imagem < 5 MB | usuários autenticados |
| `supportAttachments/{uid}/{arquivo}` | dono (criação) | ninguém pelo cliente |
| demais | negado | negado |
