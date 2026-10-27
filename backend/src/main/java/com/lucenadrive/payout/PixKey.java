package com.lucenadrive.payout;

import java.time.Instant;

/** pixKeys/{ownerId}: chave Pix de recebimento (motorista ou "PLATFORM"). Chave completa criptografada. */
public record PixKey(String ownerId, KeyType keyType, String keyEncrypted, String keyMasked, String updatedBy,
                     Instant updatedAt) {

    public enum KeyType { CPF, CNPJ, EMAIL, PHONE, EVP }
}
