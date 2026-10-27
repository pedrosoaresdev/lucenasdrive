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

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader("X-Request-Id");
        String traceId = incoming != null && incoming.matches("[A-Za-z0-9-]{8,64}") ? incoming : UUID.randomUUID().toString();
        MDC.put("traceId", traceId);
        response.setHeader("X-Request-Id", traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove("traceId");
        }
    }
}
