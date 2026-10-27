package com.lucenadrive.ledger;

/**
 * Separação exigida entre valores:
 * RECORDED – registrado contabilmente (ex.: dinheiro recebido pelo motorista fora da plataforma);
 * AWAITING_SETTLEMENT – aguardando liquidação/compensação;
 * AVAILABLE – liquidado e disponível segundo o provedor;
 * PAID – pago e confirmado pelo provedor;
 * REVERSED – estornado (ver lançamento REVERSAL correspondente).
 */
public enum LedgerStatus {
    RECORDED, AWAITING_SETTLEMENT, AVAILABLE, PAID, REVERSED
}
