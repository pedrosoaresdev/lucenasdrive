package com.lucenadrive.support;

import com.lucenadrive.payment.PaymentProvider;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Provedor fictício usado SOMENTE em testes automatizados para exercitar o fluxo completo
 * (cobrança → webhook → liquidação → saque). Não existe em código de produção.
 */
public class FakePaymentProvider implements PaymentProvider {

    public final List<ChargeRequest> charges = new CopyOnWriteArrayList<>();
    public final List<TransferRequest> transfers = new CopyOnWriteArrayList<>();
    public volatile long balanceCents = 100_000;

    @Override
    public String name() {
        return "fake";
    }

    @Override
    public boolean configured() {
        return true;
    }

    @Override
    public boolean supportsSplit() {
        return true;
    }

    @Override
    public ChargeResult createCharge(ChargeRequest request) {
        charges.add(request);
        return new ChargeResult("ch_" + charges.size(), "000201...", null, null,
                request.splitWalletId() != null);
    }

    @Override
    public TransferResult createTransfer(TransferRequest request) {
        transfers.add(request);
        return new TransferResult("tr_" + transfers.size(), "PROCESSING");
    }

    @Override
    public Optional<Long> availableBalanceCents() {
        return Optional.of(balanceCents);
    }
}
