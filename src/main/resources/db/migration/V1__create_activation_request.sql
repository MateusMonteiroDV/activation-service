CREATE TABLE activation_request (
    id                    UUID         PRIMARY KEY,
    external_id           VARCHAR(64)  NOT NULL,
    customer_id           VARCHAR(64)  NOT NULL,
    product_code          VARCHAR(64)  NOT NULL,
    client_id             VARCHAR(100) NOT NULL,
    status                VARCHAR(20)  NOT NULL,
    attempts              INTEGER      NOT NULL DEFAULT 0,
    next_attempt_at       TIMESTAMPTZ,
    processing_started_at TIMESTAMPTZ,
    last_error            VARCHAR(500),
    correlation_id        VARCHAR(64),
    version               BIGINT       NOT NULL,
    created_at            TIMESTAMPTZ  NOT NULL,
    updated_at            TIMESTAMPTZ  NOT NULL,
    -- Garantia real contra duplicidade: o banco rejeita o segundo INSERT, mesmo em requisições simultâneas.
    CONSTRAINT uk_activation_request_external_id UNIQUE (external_id),
    CONSTRAINT ck_activation_request_status CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED'))
);

-- Worker: busca apenas PENDING vencidas. Índice parcial fica pequeno porque COMPLETED/FAILED não entram.
CREATE INDEX idx_activation_request_pending
    ON activation_request (next_attempt_at)
    WHERE status = 'PENDING';

-- Reconciliação: encontra solicitações presas em PROCESSING.
CREATE INDEX idx_activation_request_processing
    ON activation_request (processing_started_at)
    WHERE status = 'PROCESSING';

-- Listagem: sempre filtrada pelo consumidor (isolamento), opcionalmente por cliente ou status, ordenada por data.
CREATE INDEX idx_activation_request_client_customer
    ON activation_request (client_id, customer_id, created_at DESC);

CREATE INDEX idx_activation_request_client_status
    ON activation_request (client_id, status, created_at DESC);
