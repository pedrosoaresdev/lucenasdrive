package com.lucenadrive.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/** Propaga um identificador de rastreio por requisição (logs estruturados e respostas de erro). */
public class RequestTraceFilter extends OncePerRequestFilter {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RequestTraceFilter.class);

    private final JsonErrorWriter errors;

    public RequestTraceFilter(JsonErrorWriter errors) {
        this.errors = errors;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader("X-Request-Id");
        String traceId = incoming != null && incoming.matches("[A-Za-z0-9-]{8,64}") ? incoming : UUID.randomUUID().toString();
        MDC.put("traceId", traceId);
        response.setHeader("X-Request-Id", traceId);
        try {
            chain.doFilter(request, response);
        } catch (RuntimeException | ServletException e) {
            // Falhas antes do controlador (filtros de segurança) também saem no formato padrão, com traceId no log.
            log.error("unexpected_filter_error path={} traceId={}", request.getRequestURI(), traceId, e);
            if (response.isCommitted() || errors == null) {
                throw e;
            }
            response.reset();
            response.setHeader("X-Request-Id", traceId);
            errors.write(request, response, com.lucenadrive.common.error.ErrorCode.INTERNAL_ERROR,
                    "Erro interno. Informe o código " + traceId.substring(0, 8) + " ao suporte.");
        } finally {
            MDC.remove("traceId");
        }
    }
}
