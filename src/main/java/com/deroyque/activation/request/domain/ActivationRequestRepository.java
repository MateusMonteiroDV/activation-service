package com.deroyque.activation.request.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ActivationRequestRepository
        extends JpaRepository<ActivationRequest, UUID>, JpaSpecificationExecutor<ActivationRequest> {

    Optional<ActivationRequest> findByExternalId(String externalId);

    /**
     * Reserva um lote de solicitações PENDING vencidas. {@code SKIP LOCKED} faz cada instância
     * pegar linhas diferentes, sem uma esperar pela outra. Deve rodar dentro de uma transação.
     */
    @Query(value = """
            SELECT * FROM activation_request
             WHERE status = 'PENDING' AND next_attempt_at <= :now
             ORDER BY next_attempt_at
             LIMIT :limit
             FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<ActivationRequest> lockDueForProcessing(@Param("now") Instant now, @Param("limit") int limit);

    /** Solicitações em PROCESSING há mais tempo que o limite (por exemplo, a instância caiu no meio). */
    @Query(value = """
            SELECT * FROM activation_request
             WHERE status = 'PROCESSING' AND processing_started_at < :startedBefore
             ORDER BY processing_started_at
             LIMIT :limit
             FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<ActivationRequest> lockStuckInProcessing(@Param("startedBefore") Instant startedBefore,
                                                  @Param("limit") int limit);
}
