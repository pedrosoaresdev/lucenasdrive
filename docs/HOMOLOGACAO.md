# Homologação no Firebase `lucenasdrive`

Ambiente para testar com o Firebase **real** (Auth, Firestore, Storage, envio de e-mails) usando apenas
**dados de teste**. O backend e os portais rodam no seu computador; nada é publicado na internet.

> Não é produção: pagamentos só no **sandbox** do Asaas (dinheiro de teste), a rota é aproximada sem chave do Google, e o App Check
> ainda não está ativo. Não cadastre clientes reais.

## Situação do projeto `lucenasdrive` (configurado em 2026-10-29)
| Item | Situação |
|---|---|
| App da Web `lucena-drive-web` | ✅ criado; `frontend/projects/homolog.firebase.ts` preenchido |
| Authentication — e-mail/senha | ✅ ativo |
| Firestore `(default)` em `southamerica-east1` (São Paulo), modo nativo | ✅ criado |
| Regras do Firestore (negação por padrão) | ✅ publicadas |
| Índices compostos (47) | ✅ criados (levam alguns minutos para ficar prontos) |
| Storage | ❌ **sem bucket**: projetos novos exigem o plano **Blaze** para o Storage. Sem ele não funcionam documentos de motoristas, fotos de perfil e anexos de suporte |
| Conta de serviço (JSON do backend) | ⏳ gerar no Console e guardar fora do projeto (passo 5 abaixo) |

## 1. Preparar o projeto no Console Firebase (uma vez)
1. **Authentication › Método de login:** ative **E-mail/senha**.
2. **Firestore Database:** crie o banco no **modo de produção**, região `southamerica-east1` (São Paulo).
   As regras do projeto (negação por padrão) serão publicadas pelo script.
3. **Storage:** crie o bucket (mesma região). Projetos novos podem exigir o plano **Blaze** para o Storage;
   defina um **alerta de orçamento** em Google Cloud › Faturamento antes de ativar.
4. **Configurações do projeto › Seus apps:** cadastre um **app da Web** (ex.: "lucena-drive-web") e copie o
   bloco `firebaseConfig` para `frontend/projects/homolog.firebase.ts`. Esses valores são públicos.
5. **Configurações do projeto › Contas de serviço › Gerar nova chave privada:** baixe o JSON e guarde-o
   **fora da pasta do projeto** (ex.: `C:\chaves\lucenasdrive-servico.json`). Esse arquivo é um segredo:
   nunca envie por chat, e-mail ou GitHub. O `.gitignore` também bloqueia `service-account*.json`.
6. (Opcional) **Cloud Messaging › Certificados push da Web:** gere a chave e cole em `HOMOLOG_FCM_VAPID_KEY`.

## 2. Subir o ambiente
Na pasta do projeto, no PowerShell:
```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\scripts\homolog-up.ps1
```
Na primeira execução o script:
- pede o caminho do JSON da conta de serviço (recusa se estiver dentro da pasta do projeto);
- gera as chaves do backend em `.homolog\keys.env` (fora do git) — **faça backup desse arquivo**: sem ele,
  CPFs criptografados e autenticadores cadastrados ficam ilegíveis;
- pede e-mail, nome, CPF e senha do **proprietário**, cria a conta no Authentication e envia o e-mail de
  confirmação. CPF e senha não são gravados em disco; o CPF fica criptografado no Firestore;
- inicia o backend (ligado ao `lucenasdrive`) e os 3 portais em `http://127.0.0.1:4200/4201/4202`.

Depois: confirme o e-mail do proprietário, entre no painel (4202) e cadastre o app autenticador.

Regras e índices já foram publicados. Para republicar depois de mudanças: `.\scripts\homolog-up.ps1 -DeployRules`
(abre o login do Firebase CLI no navegador; conclua o login na mesma máquina).

## Mapas: OpenStreetMap (padrão, gratuito) ou Google Maps (opcional)
Sem nenhuma chave, o sistema usa o **OpenStreetMap**, sem custo e sem cartão:

| Função | Serviço | Observações |
|---|---|---|
| Mapa (ruas) | tile.openstreetmap.org via Leaflet | Atribuição "© OpenStreetMap" exibida no mapa (obrigatória) |
| Busca de endereço e endereço do ponto | Photon (photon.komoot.io) | Sugestões enquanto digita; resultados fora do Brasil são descartados |
| Rota, distância e tempo (preço) | OSRM (router.project-osrm.org), no servidor | ~1 consulta/s; sem trânsito em tempo real |

**Plano B automático:** se a busca de endereços falhar, a tela orienta tocar no mapa, usar a localização ou
digitar coordenadas; se o OSRM falhar, o preço usa a estimativa em linha reta, avisada ao passageiro antes de
confirmar. Ninguém fica sem conseguir pedir ou atender corrida por causa do mapa.

**Limites honestos:** os servidores públicos do OpenStreetMap são gratuitos, mas **sem garantia** e com política
de uso justo — servem para testes e piloto pequeno. Com muitos usuários, hospede o próprio OSRM/Photon/tiles
(ou um provedor pago) e aponte `MAPS_OSRM_URL` (servidor) e `osmTileUrl`/`osmGeocoderUrl` (portais) para eles.

Google Maps continua suportado: com `HOMOLOG_MAPS_BROWSER_KEY` preenchida os portais usam Google; com a chave de
servidor (o script pergunta, opcional) as rotas usam a Google Routes API, com OSRM e linha reta como reserva.

## Pagamentos: Asaas sandbox (opcional)
1. Crie uma conta em <https://sandbox.asaas.com> (ambiente de testes, sem dinheiro real).
2. No sandbox: *Integrações → Chave de API* → gere a chave (começa com `$aact_`).
3. Rode `.\scripts\homolog-up.ps1`; na primeira vez ele pede a chave do Asaas (fica só em `.homolog\`, fora do
   git). Para trocar, apague `.homolog\asaas-sandbox-key.txt`. Deixe vazio para continuar só com dinheiro.
4. Com a chave, o passageiro passa a ver **Pix** e **Cartão**. Ao fim da corrida, o app mostra o QR Pix (ou o link
   do cartão). Pague pelo sandbox do Asaas (o painel e a documentação do Asaas explicam como simular o pagamento
   de testes); em até 1 minuto — ou ao tocar em "Já paguei — verificar" — o status muda para confirmado.
5. Split: crie uma segunda conta no sandbox para o motorista, copie o **walletId** dela e cole em
   *Ganhos → Recebimento automático* no app do motorista. As próximas corridas digitais repassam a parte dele direto.
6. Webhook: no computador local não há endereço público, então a confirmação vem pela consulta automática. O token
   do webhook (para quando houver domínio) fica em `.homolog\asaas-webhook-token.txt`.

## 3. Roteiro de teste sugerido
Mesmo roteiro do `docs/INSTALL.md` (cidade e área, tarifa, cupom, motorista, documentos, aprovação, corrida,
chat, avaliações, suporte com anexo, LGPD). Diferenças em relação ao emulador:
- e-mails de confirmação e de redefinição de senha chegam de verdade (use e-mails de teste seus);
- índices compostos recém-publicados levam alguns minutos para ficar prontos; até lá, algumas listas
  respondem erro `FAILED_PRECONDITION` no log do backend;
- a equipe vê documentos e anexos normalmente (a conta de serviço permite assinar URLs).

## 4. Limpeza
Para recomeçar do zero: apague as coleções no Console (Firestore), os usuários de teste (Authentication)
e os arquivos (Storage), e remova `.homolog\owner-done` para provisionar o proprietário de novo.
