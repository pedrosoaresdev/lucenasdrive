package com.lucenadrive.user;

import java.time.Instant;

/** userPrivate/{uid}: dados pessoais sensíveis. Sem acesso pelo cliente (regras negam tudo). */
public record UserPrivate(String id, String cpfEncrypted, String cpfHash, String birthDate, Instant createdAt) {
}
