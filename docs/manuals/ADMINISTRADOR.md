# Manual do administrador

## Acesso
1. Abra o painel administrativo e entre com e-mail e senha.
2. **Primeiro acesso:** "Gerar chave" → adicione a chave no aplicativo autenticador → digite o código de 6 dígitos →
   **guarde os 10 códigos de recuperação** (exibidos uma única vez).
3. Acessos seguintes: código do aplicativo. Perdeu o celular? "Usar código de recuperação" (cada código vale uma vez).
4. Após 5 códigos errados, o acesso fica bloqueado por 15 minutos.
5. Operações críticas (tarifas, saques, chave Pix, permissões, dados pessoais, ajustes financeiros) pedem o código
   novamente se a última confirmação tiver mais de 5 minutos.
6. "Minha segurança": veja e revogue sessões, gere novos códigos de recuperação. "Sair" encerra a sessão.

## Papéis
| Papel | Pode |
|---|---|
| SUPPORT | consultar passageiros, motoristas, corridas; moderar avaliações; atender chamados; cancelar corrida; consultar chat com justificativa |
| FINANCE | financeiro, extratos, CSV, saques da plataforma, ajustes financeiros, ganhos de motoristas, cupons (leitura) |
| ADMIN | tudo de SUPPORT; aprovar/suspender motoristas e veículos; cidades, áreas, tarifas, cupons; auditoria; LGPD; conceder SUPPORT |
| OWNER | tudo; cadastrar chave Pix da plataforma; conceder/revogar FINANCE e ADMIN |

Ninguém altera os próprios papéis. O papel OWNER não é concedido pelo painel.

## Rotina
- **Dashboard:** indicadores calculados do banco. "Saldo disponível para saque" vem do provedor de pagamentos;
  sem provedor, aparece como indisponível.
- **Motoristas › Pendente de análise:** abra o cadastro → "Visualizar" cada documento → Aprovar/Recusar (motivo
  obrigatório na recusa) → aprove o veículo → "Aprovar motorista". O motorista é notificado.
- **Suspensões:** sempre com motivo; o histórico do usuário é preservado; sessões são revogadas.
- **Corridas:** filtros por período, cidade, status, motorista e passageiro. No detalhe: linha do tempo,
  lançamentos, avaliação. "Consultar chat" exige justificativa e fica registrado na auditoria. Correções
  financeiras são **novos lançamentos de ajuste** — nada é apagado.
- **Gestão geográfica:** cadastre o município (UF, centro) e desenhe áreas clicando no mapa ou digitando vértices
  (`lat, lng` por linha). Tipos: cobertura (obrigatória para atender), zona de embarque, zona restrita e região
  tarifária (multiplicador). Desative áreas em vez de apagá-las.
- **Tarifas:** por cidade e categoria. Cada salvamento cria nova versão com autor, data, valores e motivo.
  Corridas já cotadas mantêm a versão em que foram calculadas.
- **Cupons:** código de 4–20 letras/números; percentual ou fixo; teto, valor mínimo, validade, limite total, por
  usuário, orçamento, cidades, categorias, primeira viagem, usuários elegíveis. Campanhas vencidas/esgotadas são
  encerradas automaticamente.
- **Avaliações:** ocultar comentário (a nota continua valendo), invalidar nota (remove da média), restaurar,
  encerrar contestação — sempre com justificativa. Não remova avaliações negativas legítimas.
- **Financeiro:** quatro blocos — registrado, aguardando liquidação, disponível (provedor) e pago/confirmado.
  Conciliação lista pendências internas. Exportação CSV é auditada.
- **Suporte:** fila por status/categoria, prioridade, assumir chamado, responder, escalonar. Ocorrências de
  segurança entram com prioridade alta.
- **Auditoria:** todas as operações privilegiadas e eventos de segurança (MFA, novos dispositivos, sessões).

## O olho de Deus
Menu **Visão geral › O olho de Deus** (somente Administrador e Proprietário). Mostra no mapa e na lista os passageiros
e motoristas que **ativaram** "Compartilhar minha localização com a equipe" no app e estão com o app aberto nos
últimos 5 minutos: nome, tipo (motorista ou passageiro), **cidade** (pela área de cobertura; fora delas, o nome do
lugar), posição, precisão, **IP**, **aparelho** (navegador e sistema) e horário.
- **Cidade:** escolha uma cidade para filtrar e centralizar o mapa (ou "Fora das áreas atendidas"); "Mostrar áreas"
  desenha as regiões da cidade. Os totais por cidade aparecem acima do mapa.
- **Buscar local:** digite endereço, bairro, cidade ou "lat, lng" para ir ao ponto; ◎ usa a sua localização.
- **Lista:** busca por nome, cidade ou IP; "Ver no mapa" aproxima na pessoa; "Abrir em nova aba" abre o ponto no
  OpenStreetMap; "Cadastro" leva à ficha. Atualização automática a cada 15 s (pode pausar).
- O IP é o endereço de internet de onde o app enviou a posição; em testes no mesmo computador aparece 127.0.0.1. Quem não ativou não aparece. Cada abertura do painel é registrada na
auditoria. Use apenas para suporte e segurança, conforme a política interna e a LGPD.

## Mapa operacional
Corridas ativas no mapa com as mesmas ferramentas (cidade, busca de local, áreas), filtro por etapa (procurando,
aceita, a caminho, chegou, em viagem), busca por código, motorista ou placa, e a opção de exibir embarque e destino.

## Gestão geográfica — editar regiões
Em **Comercial › Gestão geográfica**, escolha a cidade. Em cada área: **Ver** (centraliza), **Editar** e
**Ativar/Desativar**. Ao editar, os vértices aparecem como quadradinhos laranja no mapa: **arraste** para ajustar,
**clique** no mapa para acrescentar, use **Desfazer último** ou **Limpar e redesenhar**, ou cole os vértices
("lat, lng" por linha). Altere nome, tipo, multiplicador e observações e clique em **Salvar alterações** (pede
justificativa e fica na auditoria). As mudanças valem para as próximas cotações. Em **Editar dados da cidade**,
altere nome, UF e centro.
