package com.lucenadrive.payment;

import java.time.Instant;

/**
 * paymentAccounts/{driverUid}: carteira do motorista no provedor (ex.: walletId do Asaas) para
 * receber a parte de cada corrida por split automático. Sem acesso pelo cliente.
 */
public record PaymentAccount(String ownerId, String provider, String walletId, String walletMasked, String updatedBy,
                             Instant updatedAt) {
}
