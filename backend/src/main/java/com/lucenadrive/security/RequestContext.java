package com.lucenadrive.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Dados da requisição atual usados em auditoria (IP e agente). */
public final class RequestContext {

    private RequestContext() {
    }

    public static String clientIp() {
        HttpServletRequest req = current();
        if (req == null) {
            return null;
        }
        // Em produção atrás de proxy confiável, configure server.forward-headers-strategy=native.
        return req.getRemoteAddr();
    }

    public static String userAgent() {
        HttpServletRequest req = current();
        String ua = req == null ? null : req.getHeader("User-Agent");
        return ua == null ? null : ua.substring(0, Math.min(ua.length(), 300));
    }

    private static HttpServletRequest current() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs
                ? attrs.getRequest() : null;
    }
}
