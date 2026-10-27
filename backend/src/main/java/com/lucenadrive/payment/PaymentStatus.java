package com.lucenadrive.payment;

/**
 * PENDING: corrida ainda não concluída.
 * CASH_DECLARED: corrida concluída em dinheiro (valor recebido pelo motorista, fora da plataforma).
 * AWAITING_PROVIDER: cobrança criada, aguardando confirmação do provedor (webhook).
 * CONFIRMED: provedor confirmou o pagamento. FAILED, REFUNDED, CHARGEBACK: conforme o provedor.
 * NOT_CHARGED: corrida cancelada sem cobrança.
 */
public enum PaymentStatus {
    PENDING, CASH_DECLARED, AWAITING_PROVIDER, CONFIRMED, FAILED, REFUNDED, CHARGEBACK, NOT_CHARGED
}
