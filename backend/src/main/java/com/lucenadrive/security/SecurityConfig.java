package com.lucenadrive.security;

import com.lucenadrive.admin.security.AdminSessionService;
import com.lucenadrive.common.error.ErrorCode;
import com.lucenadrive.config.LucenaProperties;
import com.lucenadrive.user.UserRepository;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, IdTokenVerifier verifier, UserRepository users,
                                           AdminSessionService adminSessions, RateLimiter limiter,
                                           LucenaProperties props, JsonErrorWriter errors) throws Exception {
        var authFilter = new FirebaseAuthenticationFilter(verifier, users, adminSessions);
        var rateFilter = new RateLimitFilter(limiter, props, errors);

        http
                .csrf(c -> c.disable()) // API sem cookies: autenticação apenas por bearer token.
                .cors(c -> c.configurationSource(corsSource(props)))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .headers(h -> h
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                        .frameOptions(f -> f.deny()))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/api/v1/public/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/webhooks/**").permitAll()
                        // Login administrativo (MFA) exige papel de equipe, mas ainda não o fator.
                        .requestMatchers("/api/v1/admin/auth/**").hasAnyRole("SUPPORT", "FINANCE", "ADMIN", "OWNER")
                        // Demais rotas administrativas: papel de equipe E sessão MFA válida.
                        .requestMatchers("/api/v1/admin/**").access(staffWithMfa())
                        .requestMatchers("/api/v1/**").authenticated()
                        .anyRequest().denyAll())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) ->
                                errors.write(req, res, ErrorCode.UNAUTHENTICATED, "Autenticação necessária."))
                        .accessDeniedHandler((req, res, ex) -> {
                            boolean adminPath = req.getRequestURI().startsWith("/api/v1/admin/");
                            var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
                            boolean staffWithoutMfa = adminPath && auth != null && auth.getPrincipal() instanceof AuthUser u
                                    && u.isStaff() && !u.mfaVerified();
                            if (staffWithoutMfa) {
                                errors.write(req, res, ErrorCode.MFA_REQUIRED, "Segundo fator de autenticação necessário.");
                            } else {
                                errors.write(req, res, ErrorCode.FORBIDDEN, "Acesso negado.");
                            }
                        }))
                .addFilterBefore(new RequestTraceFilter(errors), UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(authFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(rateFilter, FirebaseAuthenticationFilter.class);
        return http.build();
    }

    private static AuthorizationManager<RequestAuthorizationContext> staffWithMfa() {
        return (authSupplier, ctx) -> {
            var auth = authSupplier.get();
            if (auth == null || !auth.isAuthenticated()) {
                return new AuthorizationDecision(false);
            }
            boolean staff = auth.getAuthorities().stream().anyMatch(g -> List.of(
                    "ROLE_SUPPORT", "ROLE_FINANCE", "ROLE_ADMIN", "ROLE_OWNER").contains(g.getAuthority()));
            boolean mfa = auth.getAuthorities().stream()
                    .anyMatch(g -> FirebaseAuthenticationFilter.MFA_AUTHORITY.equals(g.getAuthority()));
            return new AuthorizationDecision(staff && mfa);
        };
    }

    private static CorsConfigurationSource corsSource(LucenaProperties props) {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(props.security().corsAllowedOrigins());
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key",
                FirebaseAuthenticationFilter.MFA_HEADER, "X-Request-Id"));
        cfg.setExposedHeaders(List.of("X-Request-Id", "Retry-After"));
        cfg.setAllowCredentials(false);
        cfg.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cfg);
        return source;
    }
}
