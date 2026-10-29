# Status do projeto

Última atualização: 2026-10-29 · Etapas 1, 2 e 3 concluídas (etapa 3: homologação no Firebase `lucenasdrive`, redespacho, segurança em viagem, tarifas com vigência programada).

## Testes executados
Resultados reais da última execução neste repositório:

| Suíte | Comando | Resultado |
|---|---|---|
| Backend — unitários (CPF, tarifas, máquina de estados, cupons, TOTP RFC 6238, criptografia, assinatura de webhook, DocStore) | `mvn verify` | **57 testes, 0 falhas** |
| Backend — integração (contexto Spring completo, segurança real, DocStore em memória com semântica do Firestore) | `mvn verify` | **14 testes, 0 falhas** ("O olho de Deus", fluxo completo, concorrência, permissões/MFA, pagamentos, LGPD/anexos, redespacho, segurança em viagem, tarifa programada) |
| Regras do Firestore e Storage no Firebase Emulator | `npm run test:rules` | **18 testes, 0 falhas** (inclui negação de acesso a `presence` e `locationConsents`) |
| Frontend — biblioteca compartilhada (validações, formatação, estrelas acessíveis) | `ng test shared` | **13 testes, 0 falhas** |
| Build de produção dos 3 portais | `ng build …` | **sem erros nem avisos** |
| Aceitação E2E em navegador real (Chromium) contra Emulator + backend + portais | `node e2e/acceptance.cjs` | **30/30 etapas** |

Destaques dos testes de integração:
- **Aceite concorrente:** 3 motoristas aceitam a mesma corrida simultaneamente → exatamente 1 sucesso (200) e 2 recusas (409).
- **Limite de cupom sob concorrência:** 4 solicitações simultâneas para cupom com 1 uso → 1 criada, 3 recusadas (422).
- **Webhooks:** sem assinatura/assinatura inválida → 401; evento duplicado processado uma única vez.
- **Saques:** recusados acima do saldo liquidado; concluídos somente por webhook do provedor (provedor **fictício de teste**).
- **MFA:** anti-replay de código, código de recuperação de uso único, bloqueio após 5 falhas, step-up após 5 min, revogação de sessão.
- **"Cheguei":** recusado sem posição recente e a mais de 500 m do embarque; aceito perto do embarque.
- **Avaliação recíproca:** motorista avalia o passageiro uma única vez; a nota individual não fica no documento da corrida (que o passageiro lê); média do passageiro separada da do motorista.
- **LGPD:** exportação exige login recente e não expõe CPF completo nem quem avaliou; anonimização só por ADMIN/OWNER com MFA e justificativa, bloqueada com corrida ativa; remove identificação, arquivos e mensagens e preserva os valores financeiros.
- **Anexos:** só do próprio usuário, existentes no Storage, PDF/imagem até 10 MB; equipe baixa pelo backend com auditoria.
- **Permissões:** passageiro/motorista/suporte/financeiro bloqueados nas operações indevidas; admin não se autopromove nem concede OWNER/FINANCE.

## Critério de aceitação (item 29 do escopo)
| # | Critério | Situação |
|---|---|---|
| 1 | Cadastrar passageiro | ✅ (E2E) |
| 2 | Cadastrar motorista | ✅ (E2E) |
| 3 | Aprovar motorista no painel | ✅ (E2E) |
| 4 | Mapa e seleção de origem/destino | ✅ por coordenadas/geolocalização; ⚠️ mapa e busca de endereços **dependem de chave Google** (não testados com chave real) |
| 5 | Calcular tarifa | ✅ (servidor; rota aproximada sinalizada sem Routes API) |
| 6 | Solicitar corrida | ✅ |
| 7 | Motorista recebe e aceita oferta | ✅ em tempo real |
| 8 | Acompanhar corrida e localização | ✅ |
| 9–10 | Chat e troca de mensagens em tempo real | ✅ |
| 11 | Finalizar viagem | ✅ |
| 12–13 | Tela de avaliação e nota 1–5 | ✅ |
| 14 | Atualizar média do motorista | ✅ (transacional) |
| 15 | Consultar corrida no painel | ✅ |
| 16 | Comissão e lançamentos financeiros de teste | ✅ |
| 17 | Criar e aplicar cupom | ✅ |
| 18 | Alterar tarifa preservando histórico | ✅ (versões imutáveis; corrida mantém versão) |
| 19 | Impedir acessos não autorizados | ✅ (API + regras Firestore/Storage) |
| 20 | Testes essenciais com resultados documentados | ✅ (esta página) |
| — | Transferência financeira real | ❌ **pendente de provedor contratado e homologação** |

## Implementado
**Backend:** autenticação por ID token Firebase; papéis no servidor; rate limit; erros padronizados; logs com traceId;
cadastro de passageiro/motorista (CPF, idade, unicidade, CNH/EAR); documentos no Storage com validação; veículos
e placas únicas; disponibilidade; cotação no servidor por categoria com cupom; corrida idempotente; despacho em
lotes com expiração e cancelamento automático; aceite transacional; máquina de estados; localização com escrita
parcial e ETA; cancelamento com taxa; chat por corrida (sanitização, limites, deduplicação, leitura, retenção);
avaliações e moderação auditada; tarifas versionadas (janelas horárias, regiões, teto de multiplicador);
cidades e polígonos (cobertura, restrição, embarque, região tarifária); cupons completos com reserva/liberação;
ledger com idempotência e histórico de status; liquidação de corridas em dinheiro e digitais; webhooks;
saques Pix (motorista e plataforma) via porta de provedor; relatórios financeiros e conciliação interna;
notificações no app + FCM; suporte/ocorrências; LGPD (solicitações); auditoria; MFA TOTP com recuperação,
sessões, step-up, bloqueio e alerta de novo dispositivo; provisionamento seguro do OWNER; dashboard agregado.

**Etapa 2:** trava distribuída dos agendadores (lease em `schedulerLocks`); "Cheguei" exige posição recente
perto do embarque (`DISPATCH_MAX_ARRIVAL_METERS`, padrão 500 m); avaliação recíproca com média do passageiro nas
ofertas; exportação de dados pelo titular (`GET /api/v1/me/data-export`); anonimização executada no painel;
anexos no suporte (upload pelo usuário, download auditado pela equipe); push web via FCM.

**Etapa 3:** homologação no projeto `lucenasdrive` (`scripts/homolog-up.ps1`, `docs/HOMOLOGACAO.md`); redespacho
automático quando o motorista desiste antes do embarque (até 2 vezes, conversa anterior arquivada e invisível ao novo
motorista); link público temporário de acompanhamento e alerta de segurança (chamado URGENTE com a última posição);
alterações de tarifa programadas com data de vigência (versão criada no instante certo, cancelável).

**Painel "O olho de Deus" (admin):** mapa e lista, atualizados a cada 15 s, com nome e tipo (motorista/passageiro)
de quem ativou, no app, o compartilhamento opcional de localização. Somente ADMIN/OWNER; acessos auditados;
consentimento revogável a qualquer momento em Perfil. O mapa exige a chave de navegador do Google Maps.

**Frontend:** design system próprio (tema claro/escuro, acessibilidade, estados de carregamento/vazio/erro/indisponível);
PWA nos 3 portais; passageiro (landing, cadastro, login, verificação de e-mail, recuperação/redefinição de senha,
início com mapa, favoritos e recentes, cotação/cupom/pagamento, acompanhamento, chat, cancelamento, avaliação,
histórico, recibo, perfil, notificações, segurança, LGPD, suporte); motorista (cadastro, documentos, veículos,
painel online/offline, ofertas com contagem regressiva, execução da corrida com navegação externa, chat, ganhos,
saldos, chave Pix, saques, histórico, avaliações e contestação, perfil, suporte); admin (todas as áreas da API).

## Pendente / limitações conhecidas
| Item | Observação |
|---|---|
| Adaptador do provedor de pagamentos (Pix/cartão/split/transferência) | Porta e pipeline prontos; falta contratar e implementar ([INTEGRATIONS.md](INTEGRATIONS.md)) |
| Google Maps com chave real | Implementado, **não testado com chave** neste ambiente |
| Verificação de telefone (SMS) | Não implementada no fluxo de cadastro |
| App Check | Configuração de projeto pendente |
| Índices compostos em Firestore real | Definidos; o Emulator não os exige — validar após o deploy |
| Docker / Compose | Escritos, **não verificados** (sem daemon Docker no ambiente de desenvolvimento) |
| Push FCM no navegador | Implementado (service worker, permissão, token); **requer chave VAPID e projeto Firebase real** — não testado com entrega real (o emulador não entrega push) |
| Rate limit com várias instâncias | Em memória por instância (agendadores já usam trava distribuída `schedulerLocks`); usar Redis para limite global |
| Agregações administrativas em escala | Alguns relatórios iteram documentos (até 10–20 mil); criar contadores/rollups para volume alto |
| Imagens/localização no chat | Tipo de mensagem extensível; não implementado (fora do MVP) |
| Segurança em viagem | Link de acompanhamento e alerta à equipe implementados; **não há central de monitoramento 24 h** (a tela orienta ligar 190/192) |
| Homologação no Firebase `lucenasdrive` | Script e guia prontos; **não executado** — depende do `firebaseConfig` do app Web e da conta de serviço no computador do proprietário |
| Multiplicador de demanda automático | Apenas manual (por tarifa/região/janela) |
| Testes de componentes dos portais | Cobertura concentrada na biblioteca compartilhada + E2E |
| Revisões externas | Segurança (pentest), jurídica (termos, privacidade, regulação municipal) |

## Próximas etapas sugeridas
1. Contratar provedor de pagamentos e implementar o adaptador + homologação (sandbox).
2. Projeto Firebase de homologação, chaves Google restritas, App Check, deploy de regras/índices.
3. Gerar a chave VAPID e testar o push com entrega real; verificação de telefone.
4. Contadores agregados para o dashboard; limite de requisições global (Redis).
5. Pentest, revisão jurídica e piloto controlado em uma cidade.
