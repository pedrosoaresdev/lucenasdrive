# Integrações que exigem credenciais, contratos ou configuração externa

Nenhuma destas integrações está simulada como se estivesse funcionando. Sem configuração, o sistema mostra
"integração pendente" na interface e/ou responde `INTEGRATION_NOT_CONFIGURED` (HTTP 503).

| # | Integração | Situação no código | O que falta |
|---|---|---|---|
| 1 | **Projeto Firebase** (Auth, Firestore, Storage, FCM) | Implementado; validado com o Emulator Suite | Criar projetos de homologação/produção, conta de serviço, publicar regras/índices, App Check, política de senha |
| 2a | **OpenStreetMap (padrão, sem chave)** — Leaflet + tiles OSM, Photon (busca), OSRM (rotas) | Implementado, com plano B automático (linha reta sinalizada) | Para escala: hospedar OSRM/Photon/tiles próprios ou contratar provedor; públicos não têm garantia |
| 2 | **Google Maps JavaScript API + Places (New) + Geocoding** (navegador, opcional) | Implementado (`MapsLoaderService`, `MapComponent`, `PlaceSearchComponent`) | Chave de navegador restrita por referenciador HTTP (domínios dos portais) e por API; faturamento ativo; opcional `mapsMapId` |
| 3 | **Google Routes API** (servidor) | Implementado (`GoogleRoutesProvider`, ETA a cada 60 s por corrida) | Chave de servidor restrita por IP/conta e à Routes API (`MAPS_SERVER_API_KEY`); orçamento e alertas de faturamento |
| 4 | **Provedor de pagamentos: Asaas (Pix, cartão, split automático, webhooks, transferências)** | Adaptador Asaas implementado e testado contra API simulada; QR Pix e link de cartão no app; carteira do motorista para split; conciliação ativa | Chave do **sandbox** para homologar; conta aprovada e chave de **produção**; webhook com endereço público; carteiras Asaas dos motoristas |
| 5 | **Firebase Cloud Messaging (push)** | Envio no backend (`FcmPushSender`); no navegador: `PushService`, service worker próprio (`firebase-messaging-sw.js`), permissão por ação do usuário, token removido no logout | Gerar a chave VAPID (Console Firebase › Configurações do projeto › Cloud Messaging › Certificados push da Web) e preencher `fcmVapidKey` e `messagingSenderId` no environment de cada portal. Sem ela, a tela mostra "Integração pendente". O emulador não entrega push: testar em homologação |
| 6 | **Verificação de telefone** | Campo armazenado; `phoneVerified` derivado do token | Ativar Phone Auth (reCAPTCHA/App Check, custos de SMS) e o fluxo de vinculação no frontend |
| 7 | **URLs assinadas de documentos** (equipe) | Implementado (`signedReadUrl`, 5 min, auditado) | Conta de serviço com permissão de assinar (`iam.serviceAccountTokenCreator`); no Emulator retorna integração pendente |
| 8 | **Verificação de identidade/antecedentes** | Análise manual de documentos pela equipe | Contratar serviço de verificação (KYC/antecedentes) se desejado; não há consulta à Receita Federal (validação de CPF é apenas matemática) |
| 9 | **E-mail transacional** | Firebase Auth envia verificação e redefinição | Personalizar remetente/domínio e modelos no console Firebase |
| 10 | **Monitoramento/alertas** | Logs estruturados e Actuator | Destino de logs, painéis e alertas (Cloud Logging/Monitoring, Sentry etc.) |

## Google Maps — chaves, restrições, faturamento e limites
- **Chave de navegador** (pública por natureza): restrinja a *HTTP referrers* (`https://app.dominio/*`,
  `https://motorista.dominio/*`, `https://admin.dominio/*`) e às APIs *Maps JavaScript API*, *Places API (New)* e
  *Geocoding API*. Nunca use a mesma chave no servidor.
- **Chave de servidor**: restrinja à *Routes API* e, se possível, a IPs de saída fixos; guarde no Secret Manager.
- **Custos**: Maps/Places/Routes são cobrados por requisição/sessão. Controles já implementados: sessão de
  autocomplete (token por busca), debounce de 250 ms, uma rota por cotação (todas as categorias reutilizam), ETA
  limitada a 1 chamada/min por corrida. Configure orçamento e alertas no Google Cloud Billing e cotas por API.
- **Termos**: exibição de dados do Google deve seguir os termos da Google Maps Platform (ex.: mostrar em mapa
  Google, atribuição).

## Pagamentos — Asaas com split automático (escolhido pelo proprietário)
Adaptador: `payment/asaas/AsaasPaymentProvider.java` (API v3). Ativado com:

| Variável | Valor |
|---|---|
| `PAYMENTS_PROVIDER` | `asaas` |
| `PAYMENTS_API_KEY` | chave de API do Asaas (sandbox começa com `$aact_`). **Só no ambiente do servidor**; nunca no Angular nem no git |
| `PAYMENTS_ENVIRONMENT` | `sandbox` (padrão; `https://api-sandbox.asaas.com/v3`) ou `production` (`https://api.asaas.com/v3`) |
| `PAYMENTS_WEBHOOK_SECRET` | token de autenticação do webhook (o mesmo cadastrado no painel do Asaas; chega no cabeçalho `asaas-access-token`) |

**Como o dinheiro é dividido**
1. Ao concluir uma corrida Pix/cartão, o backend cria a cobrança no Asaas (cliente criado uma vez por passageiro,
   com nome e CPF — exigidos pelo Asaas; o CPF é descriptografado só em memória para essa chamada).
2. Se o motorista vinculou a **carteira Asaas** (`walletId`, tela *Ganhos → Recebimento automático*), a cobrança vai
   com `split: [{walletId, fixedValue: parte do motorista}]`: o Asaas envia a parte dele direto para a carteira dele
   e o restante (comissão) fica na conta da plataforma. **A tarifa do Asaas é descontada da parte da plataforma**
   (lançada como `PROVIDER_FEE`).
3. Sem carteira (ou se o Asaas recusar a carteira), a cobrança sai sem split: a parte do motorista entra no saldo
   dele e é sacada por Pix (transferência Asaas), como antes.
4. Com split, o ganho do motorista é marcado como **PAGO** na liquidação e **não** entra no saldo de saque
   (evita pagar duas vezes).
5. Pix: o passageiro vê QR Code e "copia e cola" no app. Cartão: link para a página segura do Asaas (os dados do
   cartão nunca passam pela plataforma).

**Confirmação** (nunca simulada): somente por (a) webhook autenticado do Asaas ou (b) consulta autenticada
`GET /payments/{id}` feita pelo servidor — a cada minuto para cobranças pendentes dos últimos 3 dias e quando o
passageiro toca em "Já paguei — verificar". Eventos tratados: `PAYMENT_CONFIRMED`, `PAYMENT_RECEIVED` (liquidação),
`PAYMENT_OVERDUE`/`PAYMENT_DELETED`/recusas (falha), `PAYMENT_REFUNDED`, `PAYMENT_CHARGEBACK_REQUESTED`,
`TRANSFER_DONE`, `TRANSFER_FAILED`/`TRANSFER_CANCELLED`. Outros eventos e cobranças que não são da plataforma
recebem 200 e são ignorados (o Asaas pausa a fila de webhooks após falhas seguidas).

**Webhook** — no painel do Asaas: *Integrações → Webhooks*, URL `https://SEU_DOMINIO/api/v1/webhooks/payments/asaas`,
token = `PAYMENTS_WEBHOOK_SECRET`, eventos de cobrança e de transferência. Em homologação no seu computador não há
endereço público: a conciliação ativa (consulta a cada minuto) cobre esse caso.

**Antes de produção (pendências reais)**
- Conta Asaas da plataforma aprovada (dados da empresa/titular) e chave de **produção** no servidor.
- Cada motorista precisa de conta/carteira Asaas própria (ou subconta criada pela plataforma via `POST /accounts`,
  que exige dados cadastrais completos — não implementado).
- Transferências (saques) pela API podem exigir validação adicional no Asaas (token/SMS ou webhook de autorização
  de saque) — configurar no painel.
- Reembolso/chargeback com split: o Asaas define como estornar a parte já repassada; revisar no contrato.
- Política de privacidade: informar o compartilhamento de nome/CPF com o processador de pagamentos.
- Taxas: consultar a tabela vigente no site do Asaas (Pix e cartão têm tarifa por transação).

**Testes**: `AsaasIT` usa um servidor local que reproduz a API do Asaas (split, recusa de carteira, QR Pix,
webhook com token, eventos alheios ignorados, conciliação). A homologação no sandbox real exige a chave do titular.

**A transferência financeira real só deve ser considerada concluída após uma transação de homologação confirmada
pelo provedor.**
