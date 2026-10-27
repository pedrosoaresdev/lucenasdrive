package com.lucenadrive.integration;

/** Operações administrativas sobre contas do Firebase Authentication. */
public interface AuthAdmin {

    /** Revoga todos os refresh tokens do usuário (encerra sessões em todos os dispositivos). */
    void revokeSessions(String uid);

    void disableAccount(String uid, boolean disabled);

    /** Exclui a conta de autenticação (após anonimização). Idempotente. */
    void deleteAccount(String uid);

    /** Resolve o uid de uma conta existente pelo e-mail (provisionamento de equipe). */
    java.util.Optional<String> uidByEmail(String email);
}
