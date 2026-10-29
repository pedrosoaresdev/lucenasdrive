# Homologação no Firebase `lucenasdrive`

Ambiente para testar com o Firebase **real** (Auth, Firestore, Storage, envio de e-mails) usando apenas
**dados de teste**. O backend e os portais rodam no seu computador; nada é publicado na internet.

> Não é produção: não há provedor de pagamentos, a rota é aproximada sem chave do Google, e o App Check
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

## Google Maps (mapa, busca de endereços e rotas)
São duas chaves no projeto `lucenasdrive` do Google Cloud (exige conta de faturamento; o uso pequeno de testes
costuma ficar dentro do crédito mensal gratuito — configure alerta de orçamento):

| Chave | APIs | Restrição | Onde vai |
|---|---|---|---|
| Navegador | Maps JavaScript API, Places API (New), Geocoding API | Sites: `http://127.0.0.1:*/*` e `http://localhost:*/*` | `HOMOLOG_MAPS_BROWSER_KEY` em `frontend/projects/homolog.firebase.ts` (pública; pode ir ao git) |
| Servidor | Routes API | Somente a Routes API | O script pergunta uma vez e guarda em `.homolog\maps-server-key.txt` (fora do git) |

Para trocar a chave de servidor: apague `.homolog\maps-server-key.txt` e rode o script de novo.
Sem chave de servidor, o preço usa a distância em linha reta (a tela avisa "rota aproximada").

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
