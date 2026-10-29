package com.lucenadrive.common.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final Clock clock;

    public GlobalExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApi(ApiException ex, HttpServletRequest req) {
        if (ex.code().status().is5xxServerError()) {
            log.warn("api_error code={} path={} msg={}", ex.code(), req.getRequestURI(), ex.getMessage());
        }
        return build(ex.code(), ex.getMessage(), req, List.of(), ex.details());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        List<ApiError.FieldError> fields = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> new ApiError.FieldError(f.getField(), f.getDefaultMessage()))
                .toList();
        return build(ErrorCode.VALIDATION_FAILED, "Dados inválidos.", req, fields, Map.of());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraint(ConstraintViolationException ex, HttpServletRequest req) {
        List<ApiError.FieldError> fields = ex.getConstraintViolations().stream()
                .map(v -> new ApiError.FieldError(v.getPropertyPath().toString(), v.getMessage()))
                .toList();
        return build(ErrorCode.VALIDATION_FAILED, "Dados inválidos.", req, fields, Map.of());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingRequestHeaderException.class, IllegalArgumentException.class})
    public ResponseEntity<ApiError> handleBadRequest(Exception ex, HttpServletRequest req) {
        log.debug("bad_request path={} err={}", req.getRequestURI(), ex.getMessage());
        return build(ErrorCode.VALIDATION_FAILED, "Requisição malformada.", req, List.of(), Map.of());
    }

    @ExceptionHandler({AccessDeniedException.class, AuthorizationDeniedException.class})
    public ResponseEntity<ApiError> handleDenied(Exception ex, HttpServletRequest req) {
        return build(ErrorCode.FORBIDDEN, "Acesso negado.", req, List.of(), Map.of());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNoResource(NoResourceFoundException ex, HttpServletRequest req) {
        return build(ErrorCode.NOT_FOUND, "Recurso não encontrado.", req, List.of(), Map.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest req) {
        String trace = MDC.get("traceId");
        log.error("unexpected_error path={} traceId={}", req.getRequestURI(), trace, ex);
        String code = trace == null ? "" : " (código " + trace.substring(0, Math.min(8, trace.length())) + ")";
        return build(ErrorCode.INTERNAL_ERROR, "Erro interno" + code + ". Tente novamente; se persistir, informe o código ao suporte.",
                req, List.of(), Map.of());
    }

    private ResponseEntity<ApiError> build(ErrorCode code, String message, HttpServletRequest req,
                                           List<ApiError.FieldError> fields, Map<String, Object> details) {
        String traceId = MDC.get("traceId");
        ApiError body = new ApiError(code.name(), message, code.status().value(), req.getRequestURI(),
                Instant.now(clock), traceId != null ? traceId : UUID.randomUUID().toString(), fields, details);
        return ResponseEntity.status(code.status()).body(body);
    }
}
