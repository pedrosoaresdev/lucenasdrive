# Lucena Drive

Plataforma de mobilidade urbana (transporte individual de passageiros por aplicativo) com três portais
independentes — **Passageiro**, **Motorista** e **Administração** — integrados a um único backend.

| Camada | Tecnologia |
|---|---|
| Frontend | Angular 22 (TypeScript, Router, Reactive Forms, signals, PWA), HTML5/CSS3, design system próprio |
| Backend | Java 21, Spring Boot 4.1, Spring Security, APIs REST, OpenAPI |
| Dados e serviços | Firebase Authentication, Cloud Firestore, Firebase Storage, Firebase Cloud Messaging |
| Mapas | Google Maps JavaScript API, Places API (New), Geocoding, Routes API |
| Pagamentos | Porta de integração para provedor marketplace (Pix, cobrança, webhooks, repasses) — **a contratar** |

> **Estado atual:** funcional em ambiente de testes (Firebase Emulator Suite). **Não está pronto para
> produção**: faltam credenciais e contratos externos (provedor de pagamentos, chaves do Google Maps,
> projeto Firebase), revisão de segurança independente e revisão jurídica. Ver
> [docs/STATUS.md](docs/STATUS.md) e [docs/SECURITY_CHECKLIST.md](docs/SECURITY_CHECKLIST.md).

## Início rápido (desenvolvimento, sem credenciais reais)

```bash
# Requisitos: Java 21, Maven 3.9+, Node 22.22.3+ ou 24.15+
./scripts/dev-up.sh
```

Sobe o Firebase Emulator Suite, o backend (perfil `dev`), provisiona um proprietário fictício e serve os
três portais:

- Passageiro: http://127.0.0.1:4200
- Motorista: http://127.0.0.1:4201
- Administração: http://127.0.0.1:4202 (`dono@lucena.test` / `SenhaDono2026x` + aplicativo autenticador)
- API (Swagger): http://127.0.0.1:8080/swagger-ui.html · Emulator UI: http://127.0.0.1:4000

Detalhes em [docs/INSTALL.md](docs/INSTALL.md).

## Testes

```bash
cd backend && mvn verify                      # 65 testes (unitários + integração)
cd firebase && npm ci && npm run test:rules   # 17 testes de regras no Emulator
cd frontend && npx ng test shared --watch=false   # 13 testes de componentes/validação
node e2e/acceptance.cjs                       # roteiro de aceitação em navegador (com o ambiente no ar)
```

Resultados da última execução em [docs/STATUS.md](docs/STATUS.md#testes-executados).

## Documentação

| Documento | Conteúdo |
|---|---|
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | Arquitetura, árvore de diretórios, decisões, versões, tempo real |
| [DATA_MODEL.md](docs/DATA_MODEL.md) | Coleções do Firestore, campos, regras, índices, retenção, privacidade |
| [API.md](docs/API.md) | Endpoints REST por módulo e papéis exigidos |
| [INSTALL.md](docs/INSTALL.md) | Instalação e execução local |
| [DEPLOY.md](docs/DEPLOY.md) | Implantação, ambientes, CI/CD, monitoramento |
| [INTEGRATIONS.md](docs/INTEGRATIONS.md) | Integrações que exigem credenciais/contratos |
| [SECURITY_CHECKLIST.md](docs/SECURITY_CHECKLIST.md) | Checklist de segurança para produção |
| [STATUS.md](docs/STATUS.md) | Funcionalidades implementadas e pendentes, testes executados |
| [LGPD.md](docs/LGPD.md) | Tratamento de dados pessoais e procedimentos |
| [manuals/](docs/manuals) | Manuais do administrador, do motorista e do passageiro |
| [legal/](docs/legal) | Modelos de Termos de Uso e Política de Privacidade (sujeitos a revisão jurídica) |
