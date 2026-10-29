# Serviço de Ativação de Produtos de Clientes (activation-service)

Microsserviço backend em **Java 21 LTS** e **Spring Boot 3.3.5** responsável por registrar solicitações de ativação de produtos de clientes, consultar seus status e integrá-las de forma confiável e assíncrona a um provedor externo.

Desenvolvido para o processo seletivo da **Deroyque IT Consulting Services**.

---

## Sumário
1. [Visão Geral e Arquitetura](#visão-geral-e-arquitetura)
   - [Endpoints da API e Modelo de Domínio](#endpoints-da-api-e-modelo-de-domínio)
   - [Ciclo de Vida da Solicitação e Concorrência](#ciclo-de-vida-da-solicitação-e-concorrência-de-domínio)
2. [Como Executar](#como-executar)
   - [Pré-requisitos](#pré-requisitos)
   - [Execução com Docker Compose (Recomendado)](#execução-com-docker-compose-recomendado)
   - [Execução Local via Maven](#execução-local-via-maven)
   - [Execução e Estratégia de Testes (Item 8)](#execução-e-estratégia-de-testes-item-8---diferencial)
3. [Decisões Técnicas](#decisões-técnicas)
   - [Estratégia Anti-Duplicidade e Idempotência (Item 3)](#estrategia-anti-duplicidade-e-idempotência-item-3)
   - [Comportamento da Integração com o Provedor (Item 5)](#comportamento-da-integração-com-o-provedor-item-5)
   - [Segurança, OAuth 2.0 e Prevenção BOLA (Item 7)](#segurança-oauth-20-e-prevenção-bola-item-7)
   - [Estratégia e Justificativa de Índices no Banco (Item 2)](#estratégia-e-justificativa-de-índices-no-banco-item-2---diferencial)
4. [Cenário de Análise de Produção (Item 10)](#cenário-de-análise-de-produção-item-10)
5. [Operação em Produção e Docker (Item 11)](#operação-em-produção-e-docker-item-11)
6. [Microsserviços em Nuvem (Diferenciais de Arquitetura)](#microsserviços-em-nuvem-diferenciais-de-arquitetura)
7. [Pontos Pendentes e Melhorias Futuras](#pontos-pendentes-e-melhorias-futuras)
8. [Uso de Ferramentas de IA](#uso-de-ferramentas-de-ia)

---

## Visão Geral e Arquitetura

O serviço foi estruturado pelo padrão **Package-by-Feature** focado em simplicidade, manutenibilidade e alta robustez sob concorrência, evitando camadas redundantes ou padrões desnecessários (*overengineering*):

```
com.deroyque.activation
├── request/                       # Ciclo de vida da ativação
│   ├── api/                       # REST Controller, DTOs (records), validação Bean Validation
│   ├── application/               # Casos de uso e Worker assíncrono (SKIP LOCKED)
│   └── domain/                    # Entidade JPA rica com transições controladas e Repository
├── provider/                      # Integração com provedor externo (RestClient, Timeouts, WireMock)
└── shared/                        # Transversais (OAuth2 Security, ProblemDetail RFC 9457, MDC Tracing)
```

### Endpoints da API e Modelo de Domínio

| Método | Endpoint | Escopo OAuth2 | Descrição |
| :--- | :--- | :--- | :--- |
| `POST` | `/api/v1/requests` | `SCOPE_activation:write` | Cria solicitação (`201 Created` com header `Location`) ou reconhece retry legítimo (`200 OK`). |
| `GET` | `/api/v1/requests/{id}` | `SCOPE_activation:read` | Consulta o status da solicitação (`200 OK` ou `404 Not Found` ProblemDetail). |
| `GET` | `/api/v1/requests?status=&customerId=&page=&size=` | `SCOPE_activation:read` | Listagem paginada e filtrada, com ordenação decrescente por data de criação. |

#### Regras de Validação (Bean Validation)
- `externalId`, `customerId` e `productCode`: Obrigatórios (`@NotBlank`), máximo de 64 caracteres (`@Size(max = 64)`), aceitando apenas caracteres alfanuméricos, ponto, hífen e sublinhado (`^[A-Za-z0-9._-]*$`).
- **DTOs**: Records Java 21 imutáveis estritamente separados das entidades JPA.
- **Datas**: Serializadas sempre em UTC no padrão ISO-8601 (`Instant`, ex.: `2026-10-26T14:03:11Z`).

### Ciclo de Vida da Solicitação e Concorrência de Domínio
```
  [PENDING]  ──(Claim do Worker)──►  [PROCESSING]
                                          │
                        ┌─────────────────┴─────────────────┐
                        ▼                                   ▼
                   [COMPLETED]                           [FAILED]
                        ▲
                        └──(Retry transitório com backoff volta a PENDING)
```

- **Transições controladas pelo domínio**: A entidade `ActivationRequest` não possui *setters* livres. Todas as alterações de status passam pelo método interno `transitionTo`, que valida a máquina de estados (ex.: `COMPLETED` não pode voltar para `PROCESSING`).
- **Comportamento em falhas temporárias**: Em caso de falha transitória do provedor (5xx, 429 ou Timeout), a solicitação **retorna para `PENDING`** com `next_attempt_at` calculado por backoff exponencial com *jitter*. Isso é fundamental para liberar a thread e o lock imediatamente, permitindo que o worker processe outras requisições prioritárias enquanto aguarda o intervalo de retry sem que o reconciliador considere a tarefa perdida.
- **Proteção Concorrente de Domínio**: Campo `@Version private Long version` com *optimistic locking* para proteger contra escritas concorrentes sobre a mesma solicitação.

---

## Como Executar

### Pré-requisitos
- **Java 21 LTS** (recomendado Eclipse Temurin)
- **Docker** e **Docker Compose**

### Execução com Docker Compose (Recomendado)
Sobe a aplicação, o banco PostgreSQL 16 e o mock do provedor via WireMock em um único comando:

```bash
docker compose up --build
```

A API estará disponível em `http://localhost:8080`.
- **Health Check**: `http://localhost:8080/actuator/health`
- **Métricas Prometheus**: `http://localhost:8080/actuator/prometheus`

### Execução Local via Maven
Se preferir executar a aplicação localmente conectando a um PostgreSQL:

```bash
# Definir variáveis de ambiente se necessário
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/activation_db
export SPRING_DATASOURCE_USERNAME=postgres
export SPRING_DATASOURCE_PASSWORD=postgrespassword
export PROVIDER_URL=http://localhost:8081

./mvnw spring-boot:run
```

### Execução e Estratégia de Testes (Item 8 - Diferencial)

A estratégia de testes divide as responsabilidades em níveis para maximizar a confiança com o menor tempo de feedback possível:

| Nível / Tipo de Teste | Classes / Escopo | Motivação (*Por quê*) |
| :--- | :--- | :--- |
| **Unitário de Domínio** | `ActivationRequestTest` | Roda em milissegundos sem inicializar o Spring. Testa transições de estado válidas, tentativas e bloqueio de transições ilegais (`InvalidStatusTransitionException`). |
| **Unitário de Aplicação** | `ActivationProcessorTest`, `ActivationRequestServiceTest` | Testa algoritmos de backoff com *jitter*, reconciliação de tarefas presas e regras de negócio com dependências mockadas via Mockito. |
| **Fatia Web (`@WebMvcTest`)** | `ActivationRequestControllerTest` | Valida serialização JSON, anotações de validação (`@Valid`), tratamento RFC 9457 ProblemDetail, cabeçalhos (`Location`) e segurança OAuth2 (respostas 401 para requisições anônimas e 403 para escopos insuficientes). |
| **Integração de Rede (WireMock)** | `HttpProviderClientTest` | Testa o cliente HTTP real (`RestClient`) contra contratos de rede reais (timeouts de conexão/leitura, cabeçalhos `Idempotency-Key`, `Retry-After`, status 400, 429 e 500) sem dependência externa frágil. |
| **Integração com Banco Real (Testcontainers)** | `ConcurrencyIntegrationTest` | Executa 10 threads simultâneas contra um contêiner oficial do **PostgreSQL 16**. Essencial para garantir que a constraint física de unicidade e a captura de `DataIntegrityViolationException` se comportem exatamente como em produção, evitando falsos positivos comuns ao H2. |
| **Arquitetura (ArchUnit)** | `ArchitectureTest` | Verifica automaticamente a conformidade com regras arquiteturais (ex.: o domínio não depende de camadas web ou de provedor). |

```bash
# Executa todos os 40 testes automatizados (unitários + integração)
./mvnw clean verify
```

---

## Decisões Técnicas

As decisões arquiteturais detalhadas estão documentadas em [`docs/adr/`](docs/adr/).

### Estratégia Anti-Duplicidade e Idempotência (Item 3)
*Consulte o [ADR 0002](docs/adr/0002-estrategia-anti-duplicidade-idempotencia.md).*

- **Por que `SELECT` antes de `INSERT` não basta sozinho?**
  Em sistemas concorrentes, duas requisições idênticas podem executar o `SELECT` na mesma fração de milissegundo. Ambas recebem "não existe" e tentam o `INSERT`, gerando duplicidade ou quebrando a aplicação se o banco não estiver preparado.
- **Solução Adotada**:
  1. Constraint única física no banco: `CONSTRAINT uk_activation_request_external_id UNIQUE (external_id)`.
  2. Inserção sem lock pessimista prévio. Caso ocorra `DataIntegrityViolationException`, a transação é descartada e o registro vencedor é consultado.
  3. **Semântica da Resposta**:
     - **Novo**: Retorna `201 Created` com cabeçalho `Location: /api/v1/requests/{id}`.
     - **Retry Legítimo** (mesmo `externalId` e mesmo conteúdo): Retorna `200 OK` com o registro existente.
     - **Conflito Malicioso/Divergente** (mesmo `externalId` com produto ou cliente diferente): Retorna `409 Conflict` (ProblemDetail RFC 9457).
     - **Requisições Concorrentes Simultâneas**: Uma thread recebe `201`, as demais recebem `200`.

### Comportamento da Integração com o Provedor (Item 5)
*Consulte o [ADR 0003](docs/adr/0003-processamento-assincrono-outbox-com-skip-locked.md) e o [ADR 0004](docs/adr/0004-resiliencia-provedor-externo.md).*

- **Processamento Assíncrono com Outbox no Banco**:
  O `POST /api/v1/requests` apenas grava a solicitação como `PENDING` e responde imediatamente. Um worker com agendamento busca lotes com `FOR UPDATE SKIP LOCKED`, desacoplando a API da latência externa e garantindo que a conexão com o banco não fique aberta durante chamadas de rede.

- **Comportamento por Retorno**:

| Retorno do Provedor | Novo Status | Nova Tentativa? | Justificativa |
| :--- | :--- | :--- | :--- |
| **Sucesso (2xx)** | `COMPLETED` | Não | Produto ativado com sucesso. |
| **HTTP 400 / 422** | `FAILED` | Não | Erro de negócio permanente (parâmetros inválidos). Repetir geraria o mesmo erro. |
| **HTTP 500 / 502 / 504** | `PENDING` | Sim (com backoff) | Falha transitória do provedor. Remarcada com backoff exponencial + full jitter. |
| **Timeout / Erro de Rede** | `PENDING` | Sim (com Idempotency-Key) | O provedor pode ter ativado ou não. O envio do header `Idempotency-Key: <requestId>` garante que a repetição não ative o produto duas vezes. |
| **HTTP 429 / 503 com Retry-After**| `PENDING` | Sim (respeitando header) | O provedor solicitou pausa por excesso de carga. O serviço agenda o retry após o intervalo exigido. |
| **Teto de Tentativas (5x)** | `FAILED` | Não | Evita consumo indefinido de recursos. Registra motivo detalhado em `last_error`. |

### Segurança, OAuth 2.0 e Prevenção BOLA (Item 7)
*Consulte o [ADR 0005](docs/adr/0005-seguranca-resource-server-isolamento-bola.md).*

1. **OAuth 2.0 Resource Server**:
   - Comunicação Machine-to-Machine (M2M) segura baseada no fluxo **Client Credentials**.
   - Validação criptográfica de tokens JWT (assinatura via JWKS, emissor `iss`, expiração `exp` e audiência `aud`).
2. **Autorização Granular por Escopo**:
   - Criação (`POST /api/v1/requests`): Exige `SCOPE_activation:write`.
   - Consulta (`GET /api/v1/requests/**`): Exige `SCOPE_activation:read`.
   - Actuator: endpoints `/actuator/health/**`, `/info` e `/prometheus` liberados para coleta de métricas.
3. **Isolamento de Consumidores (OWASP API1:2023 - BOLA)**:
   - Toda solicitação registra o `client_id` extraído do JWT.
   - Um consumidor **nunca** consegue consultar ou listar solicitações de outro consumidor. Tentativas resultam em `404 Not Found` para não expor a existência de IDs de terceiros.
4. **Armazenamento e Rotação de Credenciais do Provedor**:
   - **Armazenamento**: Em ambiente produtivo / nuvem, credenciais (como API keys, tokens de serviço e secrets de banco) são mantidas em um cofre gerenciado (**AWS Secrets Manager**, **Azure Key Vault** ou **HashiCorp Vault**) e injetadas na aplicação via variáveis de ambiente (`PROVIDER_API_KEY`) ou montadas como arquivos via Secrets do Kubernetes. Nenhuma credencial é versionada em código.
   - **Estratégia de Rotação com Zero Downtime**:
     1. O provedor externo disponibiliza suporte a *dual keys* (chave primária e secundária ativas simultaneamente).
     2. Uma nova chave é gerada e atualizada no Secrets Manager.
     3. A aplicação atualiza a credencial via endpoint de recarregamento (`/actuator/refresh`) com Spring Cloud Config ou através de um *rolling restart* gradual dos contêineres sem indisponibilidade.
     4. Uma vez que todas as instâncias estejam autenticando com a nova credencial, a chave antiga é revogada no painel do provedor.

### Estratégia e Justificativa de Índices no Banco (Item 2 - Diferencial)

Os índices criados na migração Flyway `V1__create_activation_request.sql` foram desenhados para atender aos padrões reais de acesso e concorrência:

1. `uk_activation_request_external_id UNIQUE (external_id)`:
   - **Justificativa**: Garante fisicamente a unicidade por `externalId`, criando um índice B-Tree primário que assegura busca O(log N) em deduplicações e impede concorrência duplicada diretamente na engine do PostgreSQL.
2. `idx_activation_request_pending ON activation_request (next_attempt_at) WHERE status = 'PENDING'`:
   - **Justificativa**: **Índice parcial (*partial index*)**. O worker roda a cada 2 segundos buscando apenas registros com status `PENDING` cujo `next_attempt_at <= now()`. Conforme o histórico cresce (milhões de registros `COMPLETED` ou `FAILED`), esse índice indexa apenas uma fração ínfima de linhas ativas, mantendo o consumo de memória baixo e a busca do worker com `SKIP LOCKED` ultrarrápida.
3. `idx_activation_request_processing ON activation_request (processing_started_at) WHERE status = 'PROCESSING'`:
   - **Justificativa**: Índice parcial para a tarefa de reconciliação periódica encontrar rapidamente solicitações órfãs presas em `PROCESSING`.
4. `idx_activation_request_client_customer ON activation_request (client_id, customer_id, created_at DESC)`:
   - **Justificativa**: Atende à listagem paginada (`GET /api/v1/requests?customerId=...`). Como a segurança exige isolamento por consumidor (`client_id`), a cláusula de busca sempre inclui `client_id = ? AND customer_id = ?`. A inclusão de `created_at DESC` no índice composto permite que o PostgreSQL entregue os registros ordenados diretamente das folhas do índice, dispensando ordenações custosas em memória (*filesort*).
5. `idx_activation_request_client_status ON activation_request (client_id, status, created_at DESC)`:
   - **Justificativa**: Atende à listagem paginada filtrada por status (`GET /api/v1/requests?status=...`), garantindo a mesma performance e aproveitamento de paginação indexada.

---

## Cenário de Análise de Produção (Item 10)

### O Problema
Após o deploy em produção, a latência média da API subiu de **300 ms para 2,5 s**, enquanto a CPU permaneceu em **45%** e a Memória em **60%**. O provedor externo subiu de **400 ms para 3 s** e algumas queries de banco subiram de **50 ms para 1,2 s**.

### 1. O que você investigaria primeiro?
A primeira ação é correlacionar as métricas de tempo distribuído: o aumento na API (de 300ms para 2,5s) é praticamente idêntico ao aumento de tempo do serviço externo (de 400ms para 3s). Como a CPU (45%) e a Memória (60%) estão saudáveis, o sintoma clássico é de **threads bloqueadas em I/O síncrono** aguardando resposta de rede.

### 2. Como identificaria se o problema está na aplicação, no banco ou na integração?
- **Logs estruturados com Correlation ID / Tracing (OpenTelemetry / Micrometer Observation)**:
  Analisar spans da requisição para verificar onde o tempo é consumido (ex.: span de HTTP client vs. span de JDBC connection).
- **Métricas do HikariCP**:
  Verificar `hikaricp.connections.active`, `hikaricp.connections.pending` e `hikaricp.connections.acquire.time`. Se as conexões ativas estiverem esgotadas, as queries no banco sobem de 50ms para 1,2s não porque o SQL é lento, mas porque a thread está esperando uma conexão livre no pool.

### 3. Os três sintomas podem ter uma causa comum? (Diferencial)
**Sim. A hipótese provável é a chamada HTTP ao provedor externo estar sendo executada dentro de uma transação aberta do banco de dados (padrão Connection-Leaking / Transactional over HTTP):**
1. O provedor externo ficou lento (3s).
2. Cada thread da aplicação abre uma transação JPA (`@Transactional`), faz um `SELECT`/`INSERT` e, em seguida, dispara a requisição HTTP segurando a conexão do banco aberta por 3 segundos.
3. O pool do HikariCP (ex.: padrão de 10 conexões) esgota rapidamente.
4. As outras consultas simples do banco agora levam 1,2s porque passam 1,15s na fila esperando uma conexão do pool ser liberada.
5. As requisições na API enfileiram no Tomcat/Jetty, levando o tempo médio para 2,5s sem consumir CPU excessiva (threads estão no estado `WAITING`/`TIMED_WAITING`).

### 4. Que tipo de melhoria seria considerada?
- **Arquitetura Assíncrona / Padrão Outbox (implementada neste projeto)**:
  Remover qualquer chamada remota de dentro do escopo de transações de banco. A API grava o registro em transação rápida (< 5ms) e o worker processa em segundo plano.
- **Timeouts Agressivos e Circuit Breaker**:
  Configurar connection timeout (ex.: 1s) e read timeout (ex.: 2s) no cliente HTTP com Resilience4j para abrir o circuito caso o provedor degrade.

### 5. Como validar que a correção funcionou após publicação?
1. **Canary Deployment**: Direcionar uma fração do tráfego (ex.: 5%) e monitorar os dashboards do Prometheus/Grafana.
2. **Métricas de Sucesso**:
   - Latência da API (`http.server.requests` percentil p95 e p99) normalizada para < 100ms.
   - `hikaricp.connections.pending` zerado.
   - Latência das queries de banco retornando para < 50ms.

---

## Operação em Produção e Docker (Item 11)

- **Dockerfile Multi-Stage**:
  - Imagem base de compilação: `eclipse-temurin:21-jdk-alpine`.
  - Imagem de runtime enxuta: `eclipse-temurin:21-jre-alpine` (~140MB).
  - Execução sob usuário restrito sem privilégios (`appuser:10001`).
  - Flags de contêiner JVM: `-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError`.
  - Probes nativos de Liveness e Readiness integrados no Docker Healthcheck.

---

## Microsserviços em Nuvem (Diferenciais de Arquitetura)

1. **Múltiplas Instâncias Simultâneas**:
   - O uso de `FOR UPDATE SKIP LOCKED` no PostgreSQL permite escalar horizontalmente instâncias do `activation-service` sem necessidade de locks distribuídos complexos (ex.: Redis/Redlock). Cada instância captura lotes distintos da fila sem bloqueio mútuo.
2. **Gerenciamento de Segredos e Configurações**:
   - Nenhuma credencial é versionada em código. Em nuvem (AWS/GCP/Azure), configurações ficam em variáveis de ambiente injetadas via **AWS Secrets Manager**, **HashiCorp Vault** ou Kubernetes Secrets.
3. **Comunicação Orientada a Eventos**:
   - Ao transicionar uma solicitação para `COMPLETED` ou `FAILED`, o worker pode publicar um evento em um tópico **Apache Kafka** ou **RabbitMQ** (`activation.events`), permitindo que outros serviços reajam sem acoplamento síncrono.
4. **Pipeline de CI/CD**:
   - Configurado via GitHub Actions (`.github/workflows/ci.yml`): checagem estática, testes unitários, testes com Testcontainers, verificação de regras arquiteturais (ArchUnit) e build da imagem Docker.

---

## Pontos Pendentes e Melhorias Futuras

Caso o serviço fosse evoluir em ambiente corporativo de larga escala:
- Implementar **OpenTelemetry** com agente ou biblioteca de distributed tracing integrado ao Jaeger/Grafana Tempo.
- Substituir o polling do worker por uma fila de mensageria dedicada (ex.: Kafka/SQS com Transactional Outbox CDC via Debezium) quando o volume superar dezenas de milhares de requisições por segundo.
- Adicionar documentação OpenAPI/Swagger interativa via `springdoc-openapi`.

---

## Uso de Ferramentas de IA
Conforme encorajado nas diretrizes do processo seletivo:
- **Onde foi usada**: Auxílio na criação do esqueleto inicial de testes automatizados, verificação de sintaxe de regras do ArchUnit e revisão gramatical da documentação técnica. Toda a concepção arquitetural, modelagem de domínio, controle de concorrência com locks e decisões técnicas foram validadas e testadas contra cenários reais de execução.
