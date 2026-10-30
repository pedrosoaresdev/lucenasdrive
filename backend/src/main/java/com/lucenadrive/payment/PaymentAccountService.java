package com.lucenadrive.payment;

import com.lucenadrive.audit.AuditService;
import com.lucenadrive.common.error.ApiException;
import com.lucenadrive.common.error.ErrorCode;
import com.lucenadrive.notification.NotificationService;
import com.lucenadrive.notification.NotificationType;
import com.lucenadrive.persistence.Collections;
import com.lucenadrive.persistence.DocStore;
import com.lucenadrive.security.AuthUser;
import com.lucenadrive.security.SecurityGuards;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Vínculo da carteira do motorista no provedor para receber por split automático. */
@Service
public class PaymentAccountService {

    private static final Pattern WALLET = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private final DocStore store;
    private final PaymentProvider provider;
    private final AuditService audit;
    private final NotificationService notifications;
    private final SecurityGuards guards;
    private final Clock clock;

    public PaymentAccountService(DocStore store, PaymentProvider provider, AuditService audit,
                                 NotificationService notifications, SecurityGuards guards, Clock clock) {
        this.store = store;
        this.provider = provider;
        this.audit = audit;
        this.notifications = notifications;
        this.guards = guards;
        this.clock = clock;
    }

    public record View(String provider, String walletMasked, boolean active, Instant updatedAt) {
    }

    public record Status(String provider, boolean splitSupported, View account) {
    }

    public Status status(String driverId) {
        return new Status(provider.name(), provider.configured() && provider.supportsSplit(),
                find(driverId).map(this::view).orElse(null));
    }

    public View link(AuthUser driver, String rawWalletId) {
        guards.requireRecentLogin(driver);
        if (!provider.configured() || !provider.supportsSplit()) {
            throw new ApiException(ErrorCode.INTEGRATION_NOT_CONFIGURED,
                    "Recebimento automático indisponível: provedor de pagamentos não configurado.");
        }
        String walletId = rawWalletId == null ? "" : rawWalletId.trim().toLowerCase(Locale.ROOT);
        if (!WALLET.matcher(walletId).matches()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "Identificador da carteira inválido (formato esperado: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx).");
        }
        Instant now = Instant.now(clock);
        Optional<PaymentAccount> previous = store.get(Collections.PAYMENT_ACCOUNTS, driver.uid(), PaymentAccount.class);
        PaymentAccount acc = new PaymentAccount(driver.uid(), provider.name(), walletId, mask(walletId), driver.uid(), now);
        store.set(Collections.PAYMENT_ACCOUNTS, driver.uid(), acc);
        audit.record(driver, "PAYMENT_ACCOUNT_CHANGED", "paymentAccount", driver.uid(),
                previous.map(p -> Map.<String, Object>of("provider", p.provider(), "walletMasked", p.walletMasked())).orElse(null),
                Map.of("provider", acc.provider(), "walletMasked", acc.walletMasked()), null);
        notifications.notify(driver.uid(), NotificationType.SECURITY_CHANGE, "Conta de recebimento alterada",
                "A carteira que recebe automaticamente suas corridas foi alterada. Se não foi você, contate o suporte.", Map.of());
        return view(acc);
    }

    public void unlink(AuthUser driver) {
        guards.requireRecentLogin(driver);
        store.get(Collections.PAYMENT_ACCOUNTS, driver.uid(), PaymentAccount.class).ifPresent(p -> {
            store.delete(Collections.PAYMENT_ACCOUNTS, driver.uid());
            audit.record(driver, "PAYMENT_ACCOUNT_REMOVED", "paymentAccount", driver.uid(),
                    Map.of("provider", p.provider(), "walletMasked", p.walletMasked()), null, null);
        });
    }

    /** Carteira válida para split com o provedor atual (vínculos de outro provedor são ignorados). */
    public Optional<String> walletFor(String driverId) {
        if (driverId == null || !provider.supportsSplit()) {
            return Optional.empty();
        }
        return find(driverId).filter(a -> a.provider().equalsIgnoreCase(provider.name())).map(PaymentAccount::walletId);
    }

    private Optional<PaymentAccount> find(String driverId) {
        return store.get(Collections.PAYMENT_ACCOUNTS, driverId, PaymentAccount.class);
    }

    private View view(PaymentAccount a) {
        return new View(a.provider(), a.walletMasked(), a.provider().equalsIgnoreCase(provider.name()), a.updatedAt());
    }

    static String mask(String walletId) {
        return walletId.substring(0, 4) + "••••-••••-••••-••••••••" + walletId.substring(walletId.length() - 4);
    }
}
