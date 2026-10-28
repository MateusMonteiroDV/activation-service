package com.deroyque.activation.shared.error;

import org.slf4j.MDC;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

import java.net.URI;

/** Monta ProblemDetail (RFC 9457) com tipo e identificador de correlação padronizados. */
public final class Problems {

    public static final String TYPE_BASE = "https://api.exemplo.com/problems/";

    private Problems() {
    }

    public static ProblemDetail of(HttpStatusCode status, String type, String title, String detail) {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(TYPE_BASE + type));
        problem.setTitle(title);
        addTraceId(problem);
        return problem;
    }

    public static void addTraceId(ProblemDetail problem) {
        String correlationId = MDC.get("correlationId");
        if (correlationId != null) {
            problem.setProperty("traceId", correlationId);
        }
    }
}
