package com.lucenadrive.payment;

import com.lucenadrive.security.AuthUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/rides/{rideId}/payment")
@PreAuthorize("hasRole('PASSENGER')")
@Tag(name = "Pagamento da corrida")
public class PaymentController {

    private final RideSettlementService settlement;
    private final PaymentSyncService sync;

    public PaymentController(RideSettlementService settlement, PaymentSyncService sync) {
        this.settlement = settlement;
        this.sync = sync;
    }

    @GetMapping
    public RideSettlementService.ChargeView get(@AuthenticationPrincipal AuthUser user, @PathVariable String rideId) {
        return settlement.chargeFor(rideId, user.uid());
    }

    /** Consulta o provedor agora (ex.: passageiro acabou de pagar o Pix). */
    @PostMapping("/refresh")
    public RideSettlementService.ChargeView refresh(@AuthenticationPrincipal AuthUser user, @PathVariable String rideId) {
        return sync.refreshForPassenger(rideId, user.uid());
    }
}
