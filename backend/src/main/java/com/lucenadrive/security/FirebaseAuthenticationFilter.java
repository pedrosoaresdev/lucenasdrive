package com.lucenadrive.security;

import com.lucenadrive.admin.security.AdminSessionService;
import com.lucenadrive.user.User;
import com.lucenadrive.user.UserRepository;
import com.lucenadrive.user.UserStatus;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Valida o ID token do Firebase (Authorization: Bearer) e monta o {@link AuthUser} a partir do
 * cadastro no Firestore. Para equipe administrativa, valida também o token de sessão MFA
 * (cabeçalho X-Lucena-MFA), que concede a autoridade "MFA".
 */
public class FirebaseAuthenticationFilter extends OncePerRequestFilter {

    public static final String MFA_HEADER = "X-Lucena-MFA";
    public static final String MFA_AUTHORITY = "MFA";

    private final IdTokenVerifier verifier;
    private final UserRepository users;
    private final AdminSessionService adminSessions;

    public FirebaseAuthenticationFilter(IdTokenVerifier verifier, UserRepository users, AdminSessionService adminSessions) {
        this.verifier = verifier;
        this.users = users;
        this.adminSessions = adminSessions;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                VerifiedToken token = verifier.verify(header.substring(7).trim());
                Optional<User> user = users.findById(token.uid());
                AuthUser principal = new AuthUser(token.uid(), token.email(), token.emailVerified(),
                        token.phoneNumber(),
                        user.map(User::roles).orElse(Set.of()),
                        user.map(User::status).orElse(UserStatus.ACTIVE),
                        token.authTime(), null, null, user.isPresent());
                if (principal.isStaff() && principal.status() == UserStatus.ACTIVE) {
                    String mfa = request.getHeader(MFA_HEADER);
                    if (mfa != null && !mfa.isBlank()) {
                        AuthUser base = principal;
                        principal = adminSessions.validate(mfa, base.uid())
                                .map(s -> base.withMfa(s.mfaVerifiedAt(), s.id()))
                                .orElse(base);
                    }
                }
                AuthUser finalPrincipal = principal;
                List<SimpleGrantedAuthority> authorities = Stream.concat(
                                finalPrincipal.status() == UserStatus.ACTIVE
                                        ? finalPrincipal.roles().stream().map(Role::authority)
                                        : Stream.<String>empty(),
                                finalPrincipal.mfaVerified() ? Stream.of(MFA_AUTHORITY) : Stream.<String>empty())
                        .map(SimpleGrantedAuthority::new)
                        .toList();
                var auth = new UsernamePasswordAuthenticationToken(principal, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (IdTokenVerifier.InvalidTokenException e) {
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }
}
