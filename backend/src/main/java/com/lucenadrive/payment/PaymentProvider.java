package com.lucenadrive.payment;

import java.util.Optional;

/**
 * Porta para o provedor de pagamentos (marketplace com Pix, cobrança, split, webhooks e
 * transferências). A implementação concreta depende do contrato com o provedor escolhido.
 */
public interface PaymentProvider {

    String name();

    boolean configured();

    record ChargeRequest(String idempotencyKey, String rideId, long amountCents, PaymentMethod method,
                         String payerUserId, String description) {
    }

    record ChargeResult(String providerChargeId, String pixCopyPaste, String pixQrCodeBase64) {
    }

    record TransferRequest(String idempotencyKey, long amountCents, String pixKey, String pixKeyType,
                           String description) {
    }

    record TransferResult(String providerTransferId, String status) {
    }

    ChargeResult createCharge(ChargeRequest request);

    TransferResult createTransfer(TransferRequest request);

    /** Saldo disponível informado pelo provedor (fonte da verdade para saque). */
    Optional<Long> availableBalanceCents();
}
