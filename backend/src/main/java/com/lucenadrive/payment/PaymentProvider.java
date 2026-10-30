package com.lucenadrive.payment;

import com.lucenadrive.payment.webhook.ProviderEvent;
import java.util.Map;
import java.util.Optional;

/**
 * Porta para o provedor de pagamentos (marketplace com Pix, cobrança, split, webhooks e
 * transferências). A implementação concreta depende do contrato com o provedor escolhido.
 */
public interface PaymentProvider {

    String name();

    boolean configured();

    /** Repasse automático da parte do motorista na própria cobrança (split). */
    default boolean supportsSplit() {
        return false;
    }

    /**
     * payerCpf só trafega em memória até o provedor (exigido para emitir cobranças no Brasil).
     * splitWalletId/splitCents: parte do motorista repassada automaticamente pelo provedor (null = sem split).
     */
    record ChargeRequest(String idempotencyKey, String rideId, long amountCents, PaymentMethod method,
                         String payerUserId, String description, String payerName, String payerCpf,
                         String splitWalletId, Long splitCents) {
    }

    /** checkoutUrl: página do provedor para pagar com cartão (dados do cartão nunca passam pela plataforma). */
    record ChargeResult(String providerChargeId, String pixCopyPaste, String pixQrCodeBase64, String checkoutUrl,
                        boolean splitApplied) {
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

    /**
     * Adaptador de webhook próprio do provedor (autenticação e formato). Vazio = formato genérico
     * com assinatura HMAC em X-Lucena-Signature.
     */
    default Optional<WebhookAdapter> webhookAdapter() {
        return Optional.empty();
    }

    /**
     * Consulta autenticada do estado atual de uma cobrança no provedor (conciliação quando o
     * webhook não chega). Vazio = provedor não suporta ou nada a aplicar.
     */
    default Optional<ProviderEvent> fetchChargeEvent(String providerChargeId) {
        return Optional.empty();
    }

    interface WebhookAdapter {
        /** headers com nomes em minúsculas. */
        boolean authentic(byte[] body, Map<String, String> headers, String secret);

        /** Vazio = evento que a plataforma não precisa tratar (respondido 200 para não travar a fila do provedor). */
        Optional<ProviderEvent> parse(byte[] body);
    }
}
