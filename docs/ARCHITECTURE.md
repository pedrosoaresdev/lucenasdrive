# Arquitetura do Lucena Drive

## Visão geral

```
┌──────────────────┐ ┌──────────────────┐ ┌──────────────────┐
│ Portal Passageiro│ │ Portal Motorista │ │ Portal Admin     │   Angular 22 (PWA), 3 aplicações
│  :4200           │ │  :4201           │ │  :4202 + MFA     │   + biblioteca @lucena/shared
└───┬────────▲─────┘ └───┬────────▲─────┘ └───┬──────────────┘
    │REST    │listeners  │REST    │listeners  │REST (+ X-Lucena-MFA)
    │+Bearer │(somente   │        │           │
    ▼        │ leitura)  ▼        │           ▼
┌────────────┴──────────────────────────────────────────────┐
│ Backend Spring Boot 4.1 (Java 21)                          │
│  Spring Security: valida ID token do Firebase em cada       │
│  requisição; papéis vêm de users/{uid} (gravado só aqui)    │
│  Regras de negócio, máquina de estados, despacho, tarifas, │
│  cupons, chat, avaliações, ledger, webhooks, auditoria      │
└───┬───────────────┬──────────────┬─────────────┬───────────┘
    │Admin SDK       │Admin SDK      │HTTPS        │HTTPS
    ▼                ▼               ▼             ▼
 Cloud Firestore  Firebase Auth   Google Routes  Provedor de pagamentos
 (+ Storage, FCM)                 API            (porta; a contratar)
```

**Princípio central:** todo dado é **gravado somente pelo backend** (Admin SDK). Os clientes têm, nas regras do
Firestore, apenas **leitura mínima** dos próprios dados para receber atualizações em tempo real (corrida da qual
participam, ofertas destinadas a eles, mensagens do chat da corrida, notificações). Regras do Firestore e do
Storage negam tudo por padrão. Ocultar botões no frontend é só conveniência; toda autorização é verificada no
servidor.

## Estrutura de diretórios

```
lucena-drive/
├── backend/                         Spring Boot (Maven)
│   ├── src/main/java/com/lucenadrive/
│   │   ├── LucenaDriveApplication.java
│   │   ├── config/                  Propriedades, Firebase, chaves, relógio
│   │   ├── security/                Filtro de token Firebase, papéis, rate limit, CORS, guardas
│   │   ├── persistence/             DocStore (porta) + FirestoreDocStore + InMemoryDocStore (testes)
│   │   ├── common/                  Erros padronizados, dinheiro (centavos), CPF, criptografia de campo
│   │   ├── integration/             Portas AuthAdmin/ObjectStorage/PushSender + adaptadores Firebase
│   │   ├── user/ passenger/ driver/ Cadastro, perfis, documentos, veículos, disponibilidade
│   │   ├── geo/                     Cidades, polígonos, Routes API, estimativa dev
│   │   ├── fare/                    Cálculo tarifário puro + versões auditadas
│   │   ├── coupon/                  Regras e reserva transacional de cupons
│   │   ├── ride/                    Corridas, máquina de estados, despacho, localização
│   │   ├── chat/                    Chat por corrida (envio pela API, leitura por listener)
│   │   ├── rating/                  Avaliações 1–5 estrelas, agregado consistente, moderação
│   │   ├── ledger/ payment/ payout/ finance/   Ledger, cobrança, webhooks, Pix, relatórios
│   │   ├── notification/ support/ audit/       Notificações (app + FCM), chamados, auditoria
│   │   ├── admin/                   Dashboard, gestão, MFA (TOTP), sessões, provisionamento do OWNER
│   │   └── publicapi/               Configuração pública (estado das integrações)
│   ├── src/main/resources/application.yml      Perfis padrão e dev
│   └── src/test/                    Testes unitários e de integração (contexto Spring completo)
├── frontend/                        Workspace Angular
│   ├── projects/shared/             @lucena/shared: design system, auth, API, tempo real, mapa, chat,
│   │                                estrelas, páginas de autenticação, suporte, LGPD
│   ├── projects/passenger/          Portal do passageiro
│   ├── projects/driver/             Portal do motorista
│   ├── projects/admin/              Painel administrativo
│   └── docker/                      Nginx (3 sites estáticos)
├── firebase/                        Regras, índices, configuração do Emulator, testes de regras
├── e2e/acceptance.cjs               Roteiro de aceitação (Playwright)
├── scripts/dev-up.sh                Ambiente local completo sem Docker
├── docker-compose.yml               Ambiente local com Docker (não verificado neste ambiente)
├── .github/workflows/ci.yml         CI
└── docs/                            Esta documentação
```

## Versões escolhidas (estáveis na data de implementação, 2026-10)

| Componente | Versão | Observação |
|---|---|---|
| Java | 21 (LTS) | |
| Spring Boot | 4.1.2 | Spring Framework 7, Spring Security 7, Jackson 3 |
| Firebase Admin SDK (Java) | 9.11.0 | |
| springdoc-openapi | 3.2.0 | OpenAPI em `/v3/api-docs` |
| Angular | 22.2.x | componentes standalone, signals, zoneless, builder esbuild, Vitest |
| TypeScript | 6.0 | |
| Firebase JS SDK | 13.1.0 | |
| firebase-tools | 15.34.1 | Emulator Suite |
| @firebase/rules-unit-testing | 6.0.0 | |
| Node.js | 24 LTS (mín. 22.22.3) | exigência do Angular CLI 22 |

## Decisões arquiteturais

1. **Backend como único escritor.** Elimina a necessidade de regras de escrita complexas no cliente e concentra
   validação, autorização, auditoria e idempotência em Java. O Firestore continua oferecendo tempo real via
   listeners de leitura.
2. **Porta `DocStore`.** Os serviços usam uma abstração com transações (leituras antes de escritas, como no
   Firestore). Produção usa `FirestoreDocStore`; testes usam `InMemoryDocStore`, que reproduz a semântica
   tudo-ou-nada e **impõe a mesma restrição de leitura-antes-de-escrita** (a restrição revelou um bug real
   durante a validação contra o Emulator, já corrigido).
3. **Concorrência por transação.** Aceite de corrida, reserva de cupom, avaliação + média do motorista,
   lançamentos financeiros e saques ocorrem em transações. Dois motoristas aceitando ao mesmo tempo: o
   Firestore reexecuta a transação do segundo, que encontra a corrida já aceita (`RIDE_ALREADY_ACCEPTED`).
4. **Idempotência.** Solicitação de corrida e saques exigem `Idempotency-Key`; mensagens de chat usam
   `clientMessageId` (ID determinístico `{remetente}_{clientMessageId}`); lançamentos do ledger usam chave
   determinística (`ride:{id}:commission` etc.); webhooks são registrados por `eventId`.
5. **Dinheiro em centavos (`long`).** Percentuais e multiplicadores em pontos-base (10 000 = 100 %/1,0×),
   arredondamento HALF_UP com `BigDecimal`. Nenhum `double` em cálculo financeiro.
6. **Preço definido no servidor.** O cliente pede uma cotação (`/rides/estimate`); a corrida é criada a partir da
   cotação gravada (`fareQuotes`, validade 5 min, uso único). O cliente nunca envia preço. A corrida guarda a
   composição completa e a versão da tarifa aplicada.
7. **Dados sensíveis.** CPF, CNH, chave Pix e segredo TOTP são criptografados (AES-256-GCM) e ficam em coleções
   sem acesso pelo cliente; unicidade por HMAC (`cpfIndex`, `plateIndex`). Documentos públicos (corrida) usam
   apenas primeiro nome + inicial do sobrenome.
8. **Sem simulação de integrações.** Sem chave de servidor do Google, o perfil `dev` usa uma estimativa em linha
   reta marcada como `STRAIGHT_LINE_ESTIMATE` e sinalizada na interface; em produção a opção fica desligada e a
   cotação falha com `INTEGRATION_NOT_CONFIGURED`. Sem provedor de pagamentos, Pix/cartão/saques retornam
   `INTEGRATION_NOT_CONFIGURED`; apenas dinheiro (pago ao motorista) está disponível.
9. **Três aplicações Angular + biblioteca.** Isola o painel administrativo (domínio, política de cache,
   eventualmente rede) dos apps públicos, reaproveitando design system, autenticação e componentes.

## Tempo real: escolha por canal

| Necessidade | Canal | Justificativa |
|---|---|---|
| Status da corrida, posição e ETA do motorista | Listener Firestore em `rides/{id}` | Um documento por corrida, regras por `participantIds`, reconexão automática do SDK |
| Ofertas ao motorista | Listener em `rideOffers` (`driverId == uid`, `status == PENDING`) | Entrega imediata, expiração controlada no servidor |
| Mensagens do chat | Envio via API; recebimento via listener em `rideChats/{id}/messages` | Servidor valida participante, sanitiza, limita e carimba o horário; leitura em tempo real sem WebSocket próprio |
| Notificações no app | Listener em `notifications` (`userId == uid`) | |
| Push (app fechado) | Firebase Cloud Messaging | Conteúdo genérico na tela bloqueada se o usuário preferir |
| Dashboards administrativos | Polling REST (30 s; mapa 10 s) | Agregações no servidor; equipe não tem leitura direta no Firestore |

**Não foi usado WebSocket próprio**: os listeners do Firestore já resolvem autenticação (ID token), autorização
(regras), reconexão e reenvio do estado após queda de conexão. Ordenação: mensagens ordenadas pelo
`createdAt` do servidor. Duplicidade: IDs determinísticos e `createIfAbsent`.

**Localização:** o motorista envia a posição pela API (≈4 s em corrida, ≈15 s disponível; o servidor limita a
1 a cada 2 s). Durante a corrida o backend atualiza apenas `driverPosition`/`etaSeconds` do documento da corrida
(escrita parcial, sem sobrescrever o status); a ETA usa a Routes API no máximo a cada 60 s por corrida (custo).

## Máquina de estados da corrida

```
REQUESTED ─► SEARCHING_DRIVER ─► ACCEPTED ─► DRIVER_ARRIVING ─► DRIVER_ARRIVED ─► IN_PROGRESS ─► COMPLETED
     │              │               │  └──────────────────────────►┘                    │
     └──────────────┴───────────────┴─────────────► CANCELLED ◄────────────────────────┘ (só equipe)
```

Cada transição define quem pode executá-la (`PASSENGER`, `DRIVER`, `SYSTEM`, `STAFF`) em
`RideStateMachine`. Transições inexistentes → `INVALID_STATE_TRANSITION`; ator não permitido → `FORBIDDEN`.

## Despacho

Na solicitação, o backend seleciona motoristas `available` na cidade e categoria (veículos de categoria superior
atendem inferiores), com posição atualizada há menos de 2 min e dentro de 8 km; ordena por distância e envia
ofertas a até 3 por lote, com expiração de 20 s. Um agendador expira ofertas, envia novos lotes e cancela após
3 min ou 5 lotes sem aceite (`NO_DRIVERS_AVAILABLE`, com liberação do cupom reservado).

## Autenticação administrativa

1. Login Firebase (e-mail/senha) → 2. TOTP (RFC 6238) ou código de recuperação de uso único → 3. sessão
administrativa revogável (`adminSessions`, token HMAC no cabeçalho `X-Lucena-MFA`, válida 8 h e só junto com o
ID token do mesmo usuário) → 4. operações críticas exigem MFA confirmado há ≤ 5 min (step-up). Bloqueio por
15 min após 5 falhas, anti-replay de código TOTP, alerta de novo dispositivo, eventos em `securityEvents`.
O proprietário (OWNER) é provisionado somente pelo processo `OwnerBootstrap` (variáveis de ambiente, uma vez).
