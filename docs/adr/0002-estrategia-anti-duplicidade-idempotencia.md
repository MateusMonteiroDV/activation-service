# ADR 0002: Estratégia Anti-Duplicidade e Idempotência

## Status
Aceito

## Contexto
O campo `externalId` identifica uma solicitação no sistema emissor. Quando duas requisições com o mesmo `externalId` chegam quase ao mesmo tempo, um `SELECT` prévio seguido de `INSERT` falha devido à condição de corrida (Race Condition) na janela de concorrência.

## Decisão
1. **Garantia no Banco**: Definimos uma *constraint* `UNIQUE (external_id)` no PostgreSQL via Flyway.
2. **Tratamento de Concorrência**: O método `service.create(...)` tenta o `saveAndFlush`. Caso ocorra `DataIntegrityViolationException`, captura a exceção de integridade e busca o registro que venceu a concorrência.
3. **Semântica da Resposta**:
   - **Registro Novo**: Devolve `201 Created` com header `Location: /api/v1/requests/{id}`.
   - **Retry Legítimo** (mesmo `externalId`, mesmo consumidor, mesmo `customerId` e `productCode`): Devolve `200 OK` com o registro existente. Isso garante idempotência segura em retries de rede.
   - **Conflito Malicioso/Divergente** (mesmo `externalId` com dados alterados ou emitido por outro cliente): Devolve `409 Conflict` (ProblemDetail RFC 9457).
   - **Duas requisições paralelas idênticas**: Uma vence com `201`, a outra recebe `200` de forma transparente.

## Consequências
- Concorrência validada por teste de estresse real com 10 threads concorrentes no Testcontainers (`ConcurrencyIntegrationTest`).
- Nenhuma duplicidade possível no banco de dados.
