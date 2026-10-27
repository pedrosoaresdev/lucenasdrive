package com.lucenadrive.security;

import java.time.Instant;

/** Dados extraídos de um ID token do Firebase já verificado (assinatura, emissor, expiração, revogação). */
public record VerifiedToken(String uid, String email, boolean emailVerified, String phoneNumber,
                            String name, Instant authTime, String signInProvider) {
}
