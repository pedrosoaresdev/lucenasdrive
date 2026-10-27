package com.lucenadrive.ledger;

import com.lucenadrive.common.crypto.FieldEncryptor;
import com.lucenadrive.common.money.Money;
import com.lucenadrive.persistence.Collections;
import com.lucenadrive.persistence.DocStore;
import com.lucenadrive.persistence.Query;
import com.lucenadrive.persistence.Tx;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class LedgerService {

    public record Draft(LedgerEntryType type, String account, LedgerEntry.Direction direction, long amountCents,
                        String rideId, String userId, String provider, String externalRef, LedgerStatus status,
                        String idempotencyKey, String reversalOf, String description) {
    }

    /** Saldos de uma conta separados por estado de liquidação (soma assinada: crédito − débito). */
    public record Balance(String account, Map<LedgerStatus, Long> byStatus) {
        public long of(LedgerStatus s) {
            return byStatus.getOrDefault(s, 0L);
        }
    }

    private final DocStore store;
    private final Clock clock;

    public LedgerService(DocStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    public static String idFor(String idempotencyKey) {
        return FieldEncryptor.sha256Hex(idempotencyKey).substring(0, 40);
    }

    /** Lê se o lançamento já existe (fase de leitura da transação). */
    public boolean exists(Tx tx, String idempotencyKey) {
        return tx.get(Collections.LEDGER_ENTRIES, idFor(idempotencyKey), LedgerEntry.class).isPresent();
    }

    /** Fase de escrita: cria o lançamento. Usar após checar {@link #exists}. */
    public LedgerEntry write(Tx tx, Draft d) {
        if (d.amountCents() <= 0) {
            throw new IllegalArgumentException("Lançamento deve ter valor positivo");
        }
        Instant now = Instant.now(clock);
        String id = idFor(d.idempotencyKey());
        LedgerEntry e = new LedgerEntry(id, d.type(), d.account(), d.direction(), d.amountCents(), Money.CURRENCY,
                d.rideId(), d.userId(), d.provider(), d.externalRef(), d.status(),
                List.of(new LedgerEntry.StatusChange(d.status(), now, null)), d.idempotencyKey(), d.reversalOf(),
                d.description(), now, now);
        tx.create(Collections.LEDGER_ENTRIES, id, e);
        return e;
    }

    public List<LedgerEntry> byRide(String rideId) {
        return store.query(Query.of(Collections.LEDGER_ENTRIES).eq("rideId", rideId)
                .orderBy("createdAt", Query.Direction.ASC), LedgerEntry.class);
    }

    public List<LedgerEntry> byAccount(String account, Instant from, Instant to, int limit) {
        Query q = Query.of(Collections.LEDGER_ENTRIES).eq("account", account);
        if (from != null) {
            q.where("createdAt", Query.Op.GTE, from);
        }
        if (to != null) {
            q.where("createdAt", Query.Op.LT, to);
        }
        return store.query(q.orderBy("createdAt", Query.Direction.DESC).limit(limit), LedgerEntry.class);
    }

    /**
     * Valor sacável de uma conta: lançamentos já liquidados (AVAILABLE) menos saques em curso ou
     * pagos. Não é saldo bancário — a execução do saque depende do provedor.
     */
    public long withdrawable(String account) {
        long total = 0;
        for (LedgerEntry e : byAccount(account, null, null, 10_000)) {
            if (e.type() == LedgerEntryType.PAYOUT && e.status() != LedgerStatus.REVERSED) {
                total += e.signedAmount();
            } else if (e.type() != LedgerEntryType.PAYOUT && e.status() == LedgerStatus.AVAILABLE) {
                total += e.signedAmount();
            }
        }
        return total;
    }

    /** Altera o status de liquidação preservando o histórico (fase de escrita). */
    public void transition(Tx tx, LedgerEntry e, LedgerStatus next, String reason, String externalRef) {
        if (e.status() == next || e.status() == LedgerStatus.REVERSED) {
            return;
        }
        tx.set(Collections.LEDGER_ENTRIES, e.id(), e.withStatus(next, Instant.now(clock), reason, externalRef));
    }

    public List<LedgerEntry> byRideInTx(Tx tx, String rideId) {
        return tx.query(Query.of(Collections.LEDGER_ENTRIES).eq("rideId", rideId), LedgerEntry.class);
    }

    public Balance balance(String account) {
        Map<LedgerStatus, Long> sums = new EnumMap<>(LedgerStatus.class);
        for (LedgerEntry e : byAccount(account, null, null, 10_000)) {
            sums.merge(e.status(), e.signedAmount(), Long::sum);
        }
        return new Balance(account, sums);
    }
}
