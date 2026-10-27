package com.lucenadrive.finance;

import com.lucenadrive.payout.PayoutRequest;
import com.lucenadrive.payout.PayoutService;
import com.lucenadrive.payout.PixKey;
import com.lucenadrive.security.AuthUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/drivers/me/finance")
@PreAuthorize("hasRole('DRIVER')")
@Tag(name = "Financeiro do motorista")
public class DriverFinanceController {

    private final FinanceService finance;
    private final PayoutService payouts;

    public DriverFinanceController(FinanceService finance, PayoutService payouts) {
        this.finance = finance;
        this.payouts = payouts;
    }

    public record PixKeyRequest(@NotNull PixKey.KeyType keyType, @NotBlank @Size(max = 80) String key) {
    }

    public record PayoutBody(@Min(1) long amountCents) {
    }

    @GetMapping
    public FinanceService.DriverFinance summary(@AuthenticationPrincipal AuthUser user) {
        return finance.driver(user.uid());
    }

    @GetMapping("/pix-key")
    public ResponseEntity<PayoutService.PixKeyView> pixKey(@AuthenticationPrincipal AuthUser user) {
        return payouts.pixKey(user.uid()).map(ResponseEntity::ok).orElse(ResponseEntity.noContent().build());
    }

    @PutMapping("/pix-key")
    public PayoutService.PixKeyView setPixKey(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody PixKeyRequest body) {
        return payouts.setDriverPixKey(user, body.keyType(), body.key());
    }

    @GetMapping("/payouts")
    public List<PayoutRequest> list(@AuthenticationPrincipal AuthUser user) {
        return payouts.list(user.uid(), 50);
    }

    @PostMapping("/payouts")
    @ResponseStatus(HttpStatus.CREATED)
    public PayoutRequest request(@AuthenticationPrincipal AuthUser user,
                                 @RequestHeader("Idempotency-Key") String idempotencyKey,
                                 @Valid @RequestBody PayoutBody body) {
        return payouts.requestDriverPayout(user, body.amountCents(), idempotencyKey);
    }

    @PostMapping("/payouts/{id}/cancel")
    public PayoutRequest cancel(@AuthenticationPrincipal AuthUser user, @PathVariable String id) {
        return payouts.cancel(user, id);
    }
}
