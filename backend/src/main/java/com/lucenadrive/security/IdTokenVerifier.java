package com.lucenadrive.security;

public interface IdTokenVerifier {

    /** @throws InvalidTokenException se o token for inválido, expirado ou revogado. */
    VerifiedToken verify(String idToken);

    class InvalidTokenException extends RuntimeException {
        public InvalidTokenException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
