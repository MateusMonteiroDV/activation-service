package com.deroyque.activation.shared.error;

import com.deroyque.activation.request.application.ActivationRequestNotFoundException;
import com.deroyque.activation.request.application.DuplicateExternalIdException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Converte exceções em ProblemDetail. Nunca devolve stack trace ou mensagem interna ao consumidor:
 * erros inesperados viram uma mensagem genérica e o detalhe fica no log, ligado pelo traceId.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ActivationRequestNotFoundException.class)
    ProblemDetail handleNotFound(ActivationRequestNotFoundException ex) {
        return Problems.of(HttpStatus.NOT_FOUND, "not-found", "Solicitação não encontrada", ex.getMessage());
    }

    @ExceptionHandler(DuplicateExternalIdException.class)
    ProblemDetail handleDuplicate(DuplicateExternalIdException ex) {
        return Problems.of(HttpStatus.CONFLICT, "duplicate-external-id", "Solicitação duplicada", ex.getMessage());
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ProblemDetail handleConcurrentUpdate(ObjectOptimisticLockingFailureException ex) {
        return Problems.of(HttpStatus.CONFLICT, "concurrent-update", "Atualização concorrente",
                "O registro foi alterado por outra operação. Tente novamente.");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ex) {
        log.error("Erro inesperado", ex);
        return Problems.of(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Erro inesperado",
                "Ocorreu um erro inesperado. Informe o traceId ao suporte.");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of("field", error.getField(),
                        "message", String.valueOf(error.getDefaultMessage())))
                .toList();
        var problem = Problems.of(status, "validation-error", "Dados inválidos",
                "Um ou mais campos não passaram na validação.");
        problem.setProperty("errors", errors);
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    /** Respostas geradas pelo próprio Spring (JSON malformado, método não suportado...) também ganham traceId. */
    @Override
    protected ResponseEntity<Object> createResponseEntity(@Nullable Object body, HttpHeaders headers,
                                                          HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem) {
            Problems.addTraceId(problem);
            if (problem.getType() == null || URI.create("about:blank").equals(problem.getType())) {
                problem.setType(URI.create(Problems.TYPE_BASE + "http-" + statusCode.value()));
            }
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }
}
