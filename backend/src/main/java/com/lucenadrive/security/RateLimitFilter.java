package com.lucenadrive.security;

import com.lucenadrive.config.LucenaProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Limite global por usuário autenticado (ou IP, para anônimos). */
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimiter limiter;
    private final LucenaProperties props;
    private final JsonErrorWriter errorWriter;

    public RateLimitFilter(RateLimiter limiter, LucenaProperties props, JsonErrorWriter errorWriter) {
        this.limiter = limiter;
        this.props = props;
        this.errorWriter = errorWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        String key;
        int capacity;
        if (auth != null && auth.getPrincipal() instanceof AuthUser user) {
            key = "u:" + user.uid();
            capacity = user.isStaff() ? props.rateLimit().adminRequestsPerMinute() : props.rateLimit().requestsPerMinute();
        } else {
            key = "ip:" + request.getRemoteAddr();
            capacity = props.rateLimit().requestsPerMinute();
        }
        if (!limiter.tryAcquire(key, capacity, 60_000)) {
            response.setHeader("Retry-After", "10");
            errorWriter.write(request, response, com.lucenadrive.common.error.ErrorCode.RATE_LIMITED,
                    "Muitas requisições. Aguarde alguns segundos.");
            return;
        }
        chain.doFilter(request, response);
    }
}
