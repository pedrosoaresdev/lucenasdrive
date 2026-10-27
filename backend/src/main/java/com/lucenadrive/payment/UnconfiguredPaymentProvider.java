package com.lucenadrive.payment;

import com.lucenadrive.common.error.ApiException;
import com.lucenadrive.common.error.ErrorCode;
import java.util.Optional;

/**
 * Usado enquanto nenhum provedor está contratado/configurado. Toda operação real é recusada com
 * INTEGRATION_NOT_CONFIGURED — nada é simulado.
 */
public class UnconfiguredPaymentProvider implements PaymentProvider {

    @Override
    public String name() {
        return "none";
    }

    @Override
    public boolean configured() {
        return false;
    }

    @Override
    public ChargeResult createCharge(ChargeRequest request) {
        throw notConfigured();
    }

    @Override
    public TransferResult createTransfer(TransferRequest request) {
        throw notConfigured();
    }

    @Override
    public Optional<Long> availableBalanceCents() {
        return Optional.empty();
    }

    private static ApiException notConfigured() {
        return new ApiException(ErrorCode.INTEGRATION_NOT_CONFIGURED,
                "Provedor de pagamentos não configurado. Pix, cartão e saques estão indisponíveis.");
    }
}
