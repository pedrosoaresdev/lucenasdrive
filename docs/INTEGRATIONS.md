# Integrações que exigem credenciais, contratos ou configuração externa

Nenhuma destas integrações está simulada como se estivesse funcionando. Sem configuração, o sistema mostra
"integração pendente" na interface e/ou responde `INTEGRATION_NOT_CONFIGURED` (HTTP 503).

| # | Integração | Situação no código | O que falta |
|---|---|---|---|
| 1 | **Projeto Firebase** (Auth, Firestore, Storage, FCM) | Implementado; validado com o Emulator Suite | Criar projetos de homologação/produção, conta de serviço, publicar regras/índices, App Check, política de senha |
| 2a | **OpenStreetMap (padrão, sem chave)** — Leaflet + tiles OSM, Photon (busca), OSRM (rotas) | Implementado, com plano B automático (linha reta sinalizada) | Para escala: hospedar OSRM/Photon/tiles próprios ou contratar provedor; públicos não têm garantia |
| 2 | **Google Maps JavaScript API + Places (New) + Geocoding** (navegador, opcional) | Implementado (`MapsLoaderService`, `MapComponent`, `PlaceSearchComponent`) | Chave de navegador restrita por referenciador HTTP (domínios dos portais) e por API; faturamento ativo; opcional `mapsMapId` |
| 3 | **Google Routes API** (servidor) | Implementado (`GoogleRoutesProvider`, ETA a cada 60 s por corrida) | Chave de servidor restrita por IP/conta e à Routes API (`MAPS_SERVER_API_KEY`); orçamento e alertas de faturamento |
| 4 | **Provedor de pagamentos (marketplace: Pix, cobrança, split, webhooks, transferências)** | Porta `PaymentProvider`, pipeline de webhooks (assinatura HMAC, idempotência), ledger, saques, conciliação interna; **sem adaptador de provedor real** | Escolher e contratar provedor (ex.: Asaas, Pagar.me, Mercado Pago, Stripe Connect — avaliar requisitos de subconta/split e KYC); implementar o adaptador (`PaymentProvider` + tradução do webhook para `ProviderEvent` + verificação de assinatura do provedor); sandbox; homologação; conferência automática com o extrato |
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

## Pagamentos — pontos de extensão
1. `payment/PaymentProvider.java`: `createCharge`, `createTransfer`, `availableBalanceCents`.
2. `payment/PaymentConfig.java`: registrar o adaptador conforme `lucena.payments.provider`.
3. `payment/webhook/WebhookService.parse`: substituir o formato genérico pela tradução do payload do provedor e,
   se o provedor não usar HMAC SHA-256 no corpo, implementar o verificador específico.
4. Habilitar `PIX`/`CARD` aparece automaticamente na interface quando `configured()` é verdadeiro.
5. Conciliação: implementar importação do extrato do provedor e comparação com `paymentTransactions`,
   `payoutRequests` e `ledgerEntries`.

**A transferência financeira real só deve ser considerada concluída após uma transação de homologação confirmada
pelo provedor.**
