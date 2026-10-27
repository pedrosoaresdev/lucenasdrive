package com.lucenadrive.integration.firebase;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.UserRecord;
import com.lucenadrive.common.error.ApiException;
import com.lucenadrive.common.error.ErrorCode;
import com.lucenadrive.integration.AuthAdmin;

public class FirebaseAuthAdmin implements AuthAdmin {

    private final FirebaseAuth auth;

    public FirebaseAuthAdmin(FirebaseAuth auth) {
        this.auth = auth;
    }

    @Override
    public void revokeSessions(String uid) {
        try {
            auth.revokeRefreshTokens(uid);
        } catch (FirebaseAuthException e) {
            throw new ApiException(ErrorCode.INTEGRATION_FAILURE, "Falha ao revogar sessões.");
        }
    }

    @Override
    public java.util.Optional<String> uidByEmail(String email) {
        try {
            return java.util.Optional.of(auth.getUserByEmail(email).getUid());
        } catch (FirebaseAuthException e) {
            return java.util.Optional.empty();
        }
    }

    @Override
    public void disableAccount(String uid, boolean disabled) {
        try {
            auth.updateUser(new UserRecord.UpdateRequest(uid).setDisabled(disabled));
        } catch (FirebaseAuthException e) {
            throw new ApiException(ErrorCode.INTEGRATION_FAILURE, "Falha ao atualizar a conta.");
        }
    }

    @Override
    public void deleteAccount(String uid) {
        try {
            auth.deleteUser(uid);
        } catch (FirebaseAuthException e) {
            if (e.getAuthErrorCode() != com.google.firebase.auth.AuthErrorCode.USER_NOT_FOUND) {
                throw new ApiException(ErrorCode.INTEGRATION_FAILURE, "Falha ao excluir a conta de autenticação.");
            }
        }
    }
}
