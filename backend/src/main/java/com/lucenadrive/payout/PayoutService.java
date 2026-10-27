package com.lucenadrive.payout;

import com.lucenadrive.audit.AuditService;
import com.lucenadrive.common.crypto.FieldEncryptor;
import com.lucenadrive.common.error.ApiException;
import com.lucenadrive.common.error.ErrorCode;
import com.lucenadrive.common.web.Ids;
import com.lucenadrive.ledger.LedgerEntry;
import com.lucenadrive.ledger.LedgerEntryType;
import com.lucenadrive.ledger.LedgerService;
import com.lucenadrive.ledger.LedgerStatus;
import com.lucenadrive.notification.NotificationService;
import com.lucenadrive.notification.NotificationType;
import com.lucenadrive.payment.PaymentProvider;
import com.lucenadrive.persistence.Collections;
import com.lucenadrive.persistence.DocStore;
import com.lucenadrive.persistence.Query;
import com.lucenadrive.security.AuthUser;
import com.lucenadrive.security.Role;
import com.lucenadrive.security.SecurityGuards;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Saques/repasses via Pix. Regras:
 * <ul>
 *   <li>Somente com provedor configurado — nada é simulado.</li>
 *   <li>Motorista: valor ≤ saldo AVAILABLE no ledger; exige login recente.</li>
 *   <li>Plataforma: valor ≤ saldo informado pelo PROVEDOR; exige OWNER/FINANCE e MFA recente.</li>
 *   <li>Conclusão/falha somente por webhook autenticado do provedor.</li>
 * </ul>
 */
@Service
public class PayoutService {

    public static final String PLATFORM_OWNER = "PLATFORM";
    public static final long MIN_PAYOUT_CENTS = 1_000;

    private final DocStore store;
    private final FieldEncryptor encryptor;
    private final PaymentProvider provider;
    private final LedgerService ledger;
    private final AuditService audit;
    private final NotificationService notifications;
    private final SecurityGuards guards;
    private final Clock clock;

    public PayoutService(DocStore store, FieldEncryptor encryptor, PaymentProvider provider, LedgerService ledger,
                         AuditService audit, NotificationService notifications, SecurityGuards guards, Clock clock) {
        this.store = store;
        this.encryptor = encryptor;
        this.provider = provider;
        this.ledger = ledger;
        this.audit = audit;
        this.notifications = notifications;
        this.guards = guards;
        this.clock = clock;
    }

    public record PixKeyView(PixKey.KeyType keyType, String keyMasked, Instant updatedAt) {
    }

    public Optional<PixKeyView> pixKey(String ownerId) {
        return store.get(Collections.PIX_KEYS, ownerId, PixKey.class)
                .map(k -> new PixKeyView(k.keyType(), k.keyMasked(), k.updatedAt()));
    }

    public PixKeyView setDriverPixKey(AuthUser driver, PixKey.KeyType type, String rawKey) {
        guards.requireRecentLogin(driver);
        return savePixKey(driver, driver.uid(), type, rawKey);
    }

    public PixKeyView setPlatformPixKey(AuthUser staff, PixKey.KeyType type, String rawKey) {
        guards.requireAnyRole(staff, Role.OWNER);
        guards.requireRecentMfa(staff);
        return savePixKey(staff, PLATFORM_OWNER, type, rawKey);
    }

    private PixKeyView savePixKey(AuthUser actor, String ownerId, PixKey.KeyType type, String rawKey) {
        String key = PixKeys.normalize(type, rawKey);
        Instant now = Instant.now(clock);
        PixKey pk = new PixKey(ownerId, type, encryptor.encrypt(key), PixKeys.mask(type, key), actor.uid(), now);
        Optional<PixKey> previous = store.get(Collections.PIX_KEYS, ownerId, PixKey.class);
        store.set(Collections.PIX_KEYS, ownerId, pk);
        audit.record(actor, "PIX_KEY_CHANGED", "pixKey", ownerId,
                previous.map(p -> Map.<String, Object>of("keyType", p.keyType().name(), "keyMasked", p.keyMasked())).orElse(null),
                Map.of("keyType", type.name(), "keyMasked", pk.keyMasked()), null);
        notifications.notify(actor.uid(), NotificationType.SECURITY_CHANGE, "Chave Pix alterada",
                "A chave Pix de recebimento foi alterada. Se não foi você, contate o suporte.", Map.of());
        return new PixKeyView(type, pk.keyMasked(), now);
    }

    public PayoutRequest requestDriverPayout(AuthUser driver, long amountCents, String idempotencyKey) {
        guards.requireRecentLogin(driver);
        requireProvider();
        long available = ledger.withdrawable(LedgerEntry.driverAccount(driver.uid()));
        long reserved = openPayoutsTotal(driver.uid());
        if (amountCents < MIN_PAYOUT_CENTS || amountCents > available - reserved) {
            throw new ApiException(ErrorCode.INSUFFICIENT_BALANCE, "Saldo disponível insuficiente.",
                    Map.of("availableCents", Math.max(0, available - reserved)));
        }
        return create(driver, driver.uid(), PayoutRequest.OwnerType.DRIVER, amountCents, idempotencyKey);
    }

    public PayoutRequest requestPlatformWithdrawal(AuthUser staff, long amountCents, String idempotencyKey) {
        guards.requireAnyRole(staff, Role.OWNER, Role.FINANCE);
        guards.requireRecentMfa(staff);
        requireProvider();
        long providerBalance = provider.availableBalanceCents().orElseThrow(() -> new ApiException(
                ErrorCode.INTEGRATION_FAILURE, "Não foi possível obter o saldo junto ao provedor."));
        if (amountCents < MIN_PAYOUT_CENTS || amountCents > providerBalance - openPayoutsTotal(PLATFORM_OWNER)) {
            throw new ApiException(ErrorCode.INSUFFICIENT_BALANCE, "Saldo disponível no provedor insuficiente.");
        }
        PayoutRequest req = create(staff, PLATFORM_OWNER, PayoutRequest.OwnerType.PLATFORM, amountCents, idempotencyKey);
        audit.record(staff, "PLATFORM_WITHDRAWAL_REQUESTED", "payoutRequest", req.id(), null,
                Map.of("amountCents", amountCents), null);
        return req;
    }

    private PayoutRequest create(AuthUser actor, String ownerId, PayoutRequest.OwnerType type, long amountCents,
                                 String idempotencyKey) {
        if (idempotencyKey == null || !idempotencyKey.matches("^[A-Za-z0-9-]{8,64}$")) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Cabeçalho Idempotency-Key obrigatório.");
        }
        PixKey pk = store.get(Collections.PIX_KEYS, ownerId, PixKey.class)
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED, "Cadastre uma chave Pix antes de sacar."));
        String id = LedgerService.idFor("payout:" + ownerId + ":" + idempotencyKey);
        Instant now = Instant.now(clock);
        PayoutRequest req = new PayoutRequest(id, ownerId, type, amountCents, pk.keyMasked(), pk.keyType(),
                PayoutRequest.Status.IN_VALIDATION, null, null, null, actor.uid(), idempotencyKey,
                List.of(new PayoutRequest.StatusChange(PayoutRequest.Status.REQUESTED, actor.uid(), null, now),
                        new PayoutRequest.StatusChange(PayoutRequest.Status.IN_VALIDATION, "system", null, now)), now, now);
        if (!store.createIfAbsent(Collections.PAYOUT_REQUESTS, id, req)) {
            return store.get(Collections.PAYOUT_REQUESTS, id, PayoutRequest.class).orElseThrow();
        }
        try {
            PaymentProvider.TransferResult result = provider.createTransfer(new PaymentProvider.TransferRequest(
                    "payout:" + id, amountCents, encryptor.decrypt(pk.keyEncrypted()), pk.keyType().name(),
                    "Lucena Drive - repasse " + id));
            PayoutRequest processing = req.with(PayoutRequest.Status.PROCESSING, "provider", null, Instant.now(clock),
                    result.providerTransferId(), null, null);
            store.transaction(tx -> {
                String account = type == PayoutRequest.OwnerType.DRIVER ? LedgerEntry.driverAccount(ownerId) : LedgerEntry.PLATFORM;
                String key = "payout:" + id;
                boolean exists = ledger.exists(tx, key);
                tx.set(Collections.PAYOUT_REQUESTS, id, processing);
                if (!exists) {
                    ledger.write(tx, new LedgerService.Draft(LedgerEntryType.PAYOUT, account, LedgerEntry.Direction.DEBIT,
                            amountCents, null, type == PayoutRequest.OwnerType.DRIVER ? ownerId : null, provider.name(),
                            result.providerTransferId(), LedgerStatus.AWAITING_SETTLEMENT, key, null, "Saque via Pix"));
                }
                return null;
            });
            return processing;
        } catch (ApiException e) {
            PayoutRequest failed = req.with(PayoutRequest.Status.FAILED, "provider", e.getMessage(), Instant.now(clock),
                    null, null, e.getMessage());
            store.set(Collections.PAYOUT_REQUESTS, id, failed);
            notifications.notify(actor.uid(), NotificationType.FINANCIAL_FAILURE, "Falha no saque",
                    "Não foi possível processar o saque.", Map.of("payoutId", id));
            throw e;
        }
    }

    public PayoutRequest cancel(AuthUser actor, String payoutId) {
        return store.transaction(tx -> {
            PayoutRequest p = tx.get(Collections.PAYOUT_REQUESTS, payoutId, PayoutRequest.class)
                    .orElseThrow(() -> ApiException.notFound("Saque"));
            boolean owner = p.ownerId().equals(actor.uid())
                    || (PLATFORM_OWNER.equals(p.ownerId()) && (actor.hasRole(Role.OWNER) || actor.hasRole(Role.FINANCE)));
            if (!owner) {
                throw ApiException.forbidden("Saque de outro titular.");
            }
            if (p.status() != PayoutRequest.Status.REQUESTED && p.status() != PayoutRequest.Status.IN_VALIDATION) {
                throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "Saque já enviado ao provedor; não pode ser cancelado.");
            }
            PayoutRequest next = p.with(PayoutRequest.Status.CANCELLED, actor.uid(), null, Instant.now(clock), null, null, null);
            tx.set(Collections.PAYOUT_REQUESTS, payoutId, next);
            return next;
        });
    }

    public List<PayoutRequest> list(String ownerId, int limit) {
        return store.query(Query.of(Collections.PAYOUT_REQUESTS).eq("ownerId", ownerId)
                .orderBy("createdAt", Query.Direction.DESC).limit(Math.min(limit, 100)), PayoutRequest.class);
    }

    public List<PayoutRequest> listAll(PayoutRequest.Status status, int limit) {
        Query q = Query.of(Collections.PAYOUT_REQUESTS);
        if (status != null) {
            q.eq("status", status);
        }
        return store.query(q.orderBy("createdAt", Query.Direction.DESC).limit(Math.min(limit, 200)), PayoutRequest.class);
    }

    private long openPayoutsTotal(String ownerId) {
        return list(ownerId, 100).stream().filter(p -> !p.finished() && p.status() != PayoutRequest.Status.PROCESSING)
                .mapToLong(PayoutRequest::amountCents).sum();
    }

    private void requireProvider() {
        if (!provider.configured()) {
            throw new ApiException(ErrorCode.INTEGRATION_NOT_CONFIGURED,
                    "Saques indisponíveis: provedor de pagamentos ainda não configurado.");
        }
    }
}
