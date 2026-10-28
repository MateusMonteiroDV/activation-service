# ADR 0003: Processamento Assíncrono com Padrão Outbox via SKIP LOCKED

## Status
Aceito

## Contexto
O endpoint `POST /api/v1/requests` precisa responder rapidamente e não pode ficar refém da latência ou indisponibilidade do provedor externo (o cenário do item 10 aponta que a latência externa subiu de 400ms para 3s). Além disso, não é aceitável manter conexões de banco abertas durante chamadas HTTP de rede.

## Decisão
Adotamos o padrão Outbox simplificado persistindo a própria linha da tabela `activation_request` com status `PENDING`:
1. O `POST /api/v1/requests` persiste em transação mínima o registro `PENDING` e retorna `201 Created` imediatamente.
2. O componente `ActivationProcessor` roda via `@Scheduled` e busca lotes com:
   ```sql
   SELECT * FROM activation_request
   WHERE status = 'PENDING' AND next_attempt_at <= :now
   ORDER BY next_attempt_at
   LIMIT :limit
   FOR UPDATE SKIP LOCKED;
   ```
3. A chamada HTTP ao provedor é efetuada **fora** da transação de banco de dados.
4. O resultado é gravado em uma nova transação pontual rápida.
5. Reconciliador periódico (`reconcileStuckRequests`) recupera solicitações em `PROCESSING` que ficaram órfãs se um container morrer abruptamente.

## Consequências
- A API responde em milissegundos independente do provedor externo.
- Várias instâncias do serviço podem rodar em paralelo sem conflito graças ao `SKIP LOCKED`.
- Sem retenção de threads de banco durante esperas de rede.
