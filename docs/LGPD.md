# LGPD — tratamento de dados pessoais (resumo técnico)

> Documento técnico de apoio. Não substitui avaliação jurídica nem o Relatório de Impacto (RIPD).

| Dado | Onde | Proteção | Finalidade / base legal provável |
|---|---|---|---|
| Nome, e-mail, telefone | `users` | leitura só do titular; equipe via API | execução de contrato |
| CPF | `userPrivate` (AES-256-GCM) + `cpfIndex` (HMAC) | sem leitura pelo cliente; exibição auditada a ADMIN/OWNER | prevenção à fraude, obrigações legais |
| Data de nascimento | `userPrivate` | idem | elegibilidade (idade mínima) |
| CNH | `driverPrivate` (criptografado) + máscara | idem | requisito regulatório do motorista |
| Documentos | Storage `driverDocuments/` | sem leitura pelo cliente; URL assinada 5 min auditada | análise de cadastro |
| Localização | `driverLocations`, `rides.driverPosition` | sem leitura do motorista por terceiros; passageiro vê apenas durante a própria corrida | execução do serviço, segurança |
| Mensagens de chat | `rideChats/*/messages` | participantes; equipe com justificativa auditada | execução do serviço; apuração de ocorrências |
| Avaliações | `ratings` | resumo público sem identificar avaliador | qualidade do serviço |
| Financeiro | `ledgerEntries`, `paymentTransactions`, `payoutRequests`, `pixKeys` (criptografada) | sem leitura pelo cliente | obrigações legais/fiscais |

## Direitos do titular (art. 18)
- **Cópia dos dados (acesso/portabilidade):** Perfil › Privacidade › "Baixar meus dados" chama
  `GET /api/v1/me/data-export` e gera um JSON (conta, perfis, veículos, corridas, mensagens enviadas, avaliações
  dadas e recebidas sem identificar quem avaliou, chamados, notificações, lançamentos financeiros do motorista).
  Exige login recente (5 min; a tela pede a senha), limite de 3 por hora, CPF apenas mascarado. Cada exportação
  gera uma solicitação concluída e um registro de auditoria `DATA_EXPORTED`.
- O titular registra os demais pedidos em Perfil › Privacidade (`/me/privacy-requests`): correção, exclusão,
  revogação de consentimento.
- A equipe (ADMIN/OWNER) acompanha em Admin › Solicitações LGPD; toda decisão é auditada.

## Exclusão — anonimização executada no painel
Admin › Solicitações LGPD › "Anonimizar conta" (`POST /api/v1/admin/privacy-requests/{id}/anonymize`), somente
ADMIN/OWNER, com justificativa (mín. 10 caracteres) e MFA recente. O servidor:
1. **Bloqueia** se houver corrida ativa, saldo a repassar ao motorista, repasse em andamento, ou se a conta
   tiver papel administrativo (remover as permissões antes).
2. Desativa a conta no Firebase Auth e revoga as sessões.
3. Apaga `userPrivate` (CPF criptografado, nascimento), `cpfIndex`, `driverPrivate` (CNH), `pixKeys`,
   `driverLocations`, `notifications`, `pushTokens`; libera o `plateIndex`.
4. Anonimiza `users` (nome "Removido (LGPD)", sem e-mail/telefone/foto/CPF mascarado, status `DELETED`),
   favoritos e destinos recentes, documentos do motorista, placas dos veículos.
5. Apaga os arquivos no Storage (`driverDocuments/`, `profilePhotos/`, `supportAttachments/` do titular).
6. Nas corridas: remove nome, foto, placa, endereços e trajeto; coordenadas ficam com ~1 km de precisão.
   Mensagens de chat enviadas pelo titular viram "[mensagem removida]"; comentários de avaliações são apagados.
7. Mantém valores de corridas, transações, lançamentos e repasses (obrigação legal, art. 16, I), referenciando
   apenas o identificador pseudônimo; chamados de suporte são mantidos para defesa em processos.
8. Conclui a solicitação, registra `ACCOUNT_ANONYMIZED` na auditoria e exclui a conta do Firebase Auth.

A operação é idempotente nos passos internos: se falhar no meio (ex.: Storage indisponível), pode ser repetida
enquanto a solicitação não estiver concluída. A definição final de prazos de retenção depende do jurídico.

## Localização opcional ("O olho de Deus")
- Base legal: **consentimento** (art. 7º, I), livre e revogável. O app só convida; nada é enviado sem o "Permitir"
  e sem a permissão do navegador. "Agora não" não volta a perguntar por 7 dias.
- Prova do consentimento: `locationConsents/{uid}` com versão do texto e histórico de ativações/desativações.
- Minimização: só a **última** posição (`presence/{uid}`: nome, tipo, lat/lng, precisão, horário, IP, navegador e
  cidade atendida), enviada no
  máximo a cada 10–20 s com o app aberto; some do painel após 5 min e é **apagada após 30 min**; desligar apaga na hora.
- Acesso: somente ADMIN/OWNER (verificado no servidor, com MFA); abertura do painel registrada na auditoria
  (`GOD_EYE_VIEWED`). Clientes não leem essas coleções (regras do Firestore).
- Exportação inclui o consentimento e a última posição; a anonimização apaga a posição.
- **Recomendação:** validar com o jurídico o uso de posições de passageiros fora de corridas e definir a política
  interna de quem pode consultar o painel e para quais finalidades.

## Retenção
| Dado | Prazo |
|---|---|
| Chat | leitura pelos participantes por 7 dias após a corrida; exclusão após 180 dias (configurável) |
| Corridas, financeiro, auditoria | definir com o jurídico (obrigações fiscais/civis) |
| Sessões administrativas | expiram em 8 h; registros mantidos para auditoria |
