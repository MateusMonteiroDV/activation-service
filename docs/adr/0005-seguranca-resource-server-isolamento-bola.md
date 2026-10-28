# ADR 0005: Segurança, OAuth 2.0 Resource Server e Isolamento BOLA

## Status
Aceito

## Contexto
A API é exposta para múltiplos sistemas parceiros e consumidores internos. É indispensável garantir autenticação JWT com escopos de granularidade fina e impedir que um consumidor consiga consultar ou alterar solicitações criadas por outro (vulnerabilidade Broken Object Level Authorization - BOLA / OWASP API1:2023).

## Decisão
1. **OAuth 2.0 Resource Server**:
   - Valida JWT emitido via fluxo *Client Credentials* (o fluxo recomendado para comunicação Machine-to-Machine / M2M entre sistemas).
   - Validação de assinatura criptográfica (JWKS), data de expiração (`exp`), emissor (`iss`) e audiência (`aud`).
2. **Autorização por Escopos**:
   - `SCOPE_activation:write`: Obrigatório para `POST /api/v1/requests`.
   - `SCOPE_activation:read`: Obrigatório para `GET /api/v1/requests/**`.
3. **Isolamento de Consumidores (BOLA)**:
   - A coluna `client_id` na entidade registra o claim `client_id` (ou fallback para `azp`/`sub`) extraído do token autenticado.
   - Toda consulta por ID ou listagem filtra obrigatoriamente por `clientId`.
   - Tentativas de consultar registro de outro consumidor retornam `404 Not Found` (não revelando nem a existência do identificador).

## Consequências
- Proteção nativa contra BOLA testada em `ActivationRequestServiceTest`.
- Testes unitários com `@WebMvcTest` e `jwt()` cobrindo respostas 401 (sem token) e 403 (escopo inadequado).
