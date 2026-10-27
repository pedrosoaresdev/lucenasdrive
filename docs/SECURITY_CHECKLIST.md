# Checklist de segurança para produção

Marque cada item antes de operar com usuários reais.

## Identidade e acesso
- [ ] Projeto Firebase de produção separado; contas de teste removidas.
- [ ] Política de senha do Firebase Auth (≥ 10, letras e números) e proteção contra enumeração de e-mails ativas.
- [ ] Verificação de e-mail exigida (`REQUIRE_VERIFIED_EMAIL=true`).
- [ ] App Check ativado e **imposto** para Firestore e Storage.
- [ ] OWNER provisionado por `OwnerBootstrap` e variáveis de bootstrap removidas; TOTP cadastrado; códigos de
      recuperação guardados offline.
- [ ] Revisão dos papéis da equipe (privilégio mínimo); FINANCE/ADMIN só concedidos pelo OWNER.
- [ ] Procedimento documentado para recuperação de conta administrativa (perda de autenticador + códigos).
- [ ] Considerar passkeys/WebAuthn ou Identity Platform MFA como evolução do TOTP próprio.

## Segredos e chaves
- [ ] `LUCENA_DATA_ENCRYPTION_KEY` e `LUCENA_ADMIN_MFA_TOKEN_SECRET` gerados aleatoriamente (32 bytes) e
      armazenados no Secret Manager; plano de rotação e backup seguro da chave de dados.
- [ ] Nenhum segredo no repositório (varredura com gitleaks/trufflehog no CI).
- [ ] Chaves Google com restrição de aplicação e de API; orçamento e alertas de faturamento.
- [ ] Segredo de webhook do provedor configurado; IPs do provedor permitidos se suportado.

## Dados e regras
- [ ] Regras do Firestore/Storage publicadas e testes (`npm run test:rules`) verdes no pipeline.
- [ ] Índices publicados (`firestore.indexes.json`).
- [ ] Backups/exportações agendadas do Firestore e teste de restauração.
- [ ] Retenção: job de chat ativo; política de retenção de corridas/financeiro definida pelo jurídico.
- [ ] Procedimento LGPD (exportação, anonimização) testado ([LGPD.md](LGPD.md)).

## Aplicação
- [ ] `SWAGGER_ENABLED=false`; `/actuator/**` (exceto health) bloqueado na borda.
- [ ] CORS limitado aos domínios oficiais.
- [ ] HTTPS em todos os domínios; HSTS; CSP restritiva nos portais; cabeçalhos de segurança.
- [ ] `FORWARD_HEADERS_STRATEGY=native` somente atrás de proxy confiável (IP correto na auditoria/rate limit).
- [ ] Rate limit distribuído (Redis/Cloud Armor) se houver mais de uma instância.
- [ ] Lock distribuído para agendadores com múltiplas instâncias.
- [ ] Dependências atualizadas e varredura (Dependabot, `npm audit`, OWASP Dependency-Check).
- [ ] `MAPS_ALLOW_STRAIGHT_LINE_FALLBACK=false`.
- [ ] Teste de intrusão / revisão de segurança independente (API, regras, painel admin, fluxo financeiro).

## Financeiro
- [ ] Provedor contratado, adaptador implementado e homologado com transações reais de sandbox.
- [ ] Verificação de assinatura do webhook conforme o provedor; replay protection (idempotência já existe).
- [ ] Conciliação automática com o extrato do provedor.
- [ ] Limites diários de saque e dupla aprovação para saques da plataforma acima de um valor.

## Operação e legal
- [ ] Termos de uso e política de privacidade revisados por advogado; versões publicadas e referenciadas.
- [ ] Autorizações municipais/regulatórias para transporte remunerado em cada cidade atendida.
- [ ] Encarregado (DPO) designado e canal de atendimento ao titular.
- [ ] Plano de resposta a incidentes e comunicação à ANPD.
- [ ] Monitoramento e alertas configurados; on-call definido.
