package com.lucenadrive.ledger;

public enum LedgerEntryType {
    /** Valor da corrida paga em dinheiro diretamente ao motorista (informativo, fora da plataforma). */
    RIDE_FARE_CASH,
    /** Valor da corrida recebido pela plataforma via provedor (Pix/cartão). */
    RIDE_PAYMENT,
    /** Ganho do motorista em corrida paga via plataforma. */
    DRIVER_EARNING,
    /** Comissão da plataforma. */
    PLATFORM_COMMISSION,
    /** Comissão devida pelo motorista (corridas em dinheiro). */
    DRIVER_COMMISSION_DUE,
    /** Desconto de cupom subsidiado pela plataforma. */
    COUPON_SUBSIDY,
    /** Taxa de cancelamento. */
    CANCELLATION_FEE,
    /** Tarifa cobrada pelo provedor de pagamentos. */
    PROVIDER_FEE,
    REFUND,
    CHARGEBACK,
    PAYOUT,
    ADJUSTMENT,
    REVERSAL
}
