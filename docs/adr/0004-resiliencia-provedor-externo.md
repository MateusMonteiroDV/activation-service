# ADR 0004: Resiliência na Integração com o Provedor Externo

## Status
Aceito

## Contexto
O provedor externo pode falhar de formas variadas: erros de cliente (400), rate limiting (429), indisponibilidade temporária (500, 503) e timeouts de conexão ou leitura. Em cenários de timeout, não há garantia se a ativação ocorreu ou não.

## Decisão
1. **Idempotência no Provedor**: A cada requisição `POST /provider/v1/activate`, enviamos o header `Idempotency-Key: <requestId>`. Em caso de retry por timeout, a chave informa ao provedor que se trata da mesma tentativa, impedindo cobrança ou ativação duplicada.
2. **Classificação de Retornos**:
   - `2xx`: Sucesso $\rightarrow$ status `COMPLETED`.
   - `400 / 422`: Erro de negócio/parâmetro permanente $\rightarrow$ status `FAILED`.
   - `429 / 503 com Retry-After`: Falha temporária $\rightarrow$ remarcação respeitando o tempo solicitado no header HTTP.
   - `500 / 502 / 504 / Timeout / Erro de Rede`: Falha transitória $\rightarrow$ reagendamento com backoff exponencial + full jitter.
3. **Limite de Tentativas**: Limite padrão de 5 tentativas. Ao atingir o teto, transiciona para `FAILED` gravando `lastError`.
4. **Timeouts Explícitos**: Configurados via `JdkClientHttpRequestFactory` com HTTP/1.1 para evitar overhead de negociação TLS/h2.

## Consequências
- Comportamento testado deterministicamente via WireMock em `HttpProviderClientTest`.
- Eliminação de tempestades de retry (*retry storms*) via jitter e backoff progressivo.
