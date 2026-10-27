package com.lucenadrive.payment;

/**
 * CASH: pago diretamente ao motorista (não passa pela plataforma).
 * PIX/CARD: exigem provedor de pagamentos configurado.
 */
public enum PaymentMethod {
    CASH, PIX, CARD;

    public boolean requiresProvider() {
        return this != CASH;
    }
}
