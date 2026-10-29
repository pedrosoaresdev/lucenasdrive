package com.lucenadrive.admin.reports;

import com.lucenadrive.audit.AuditService;
import com.lucenadrive.common.error.ApiException;
import com.lucenadrive.common.error.ErrorCode;
import com.lucenadrive.fare.FareBreakdown;
import com.lucenadrive.ledger.LedgerEntry;
import com.lucenadrive.persistence.Collections;
import com.lucenadrive.persistence.DocStore;
import com.lucenadrive.persistence.Query;
import com.lucenadrive.ride.Ride;
import com.lucenadrive.security.AuthUser;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Relatórios para planilha (corridas e lançamentos financeiros). Sem CPF, e-mail ou telefone; exportações auditadas. */
@Service
public class ReportService {

    static final int MAX_ROWS = 20_000;
    static final Duration MAX_PERIOD = Duration.ofDays(366);

    private final DocStore store;
    private final AuditService audit;
    private final Clock clock;

    public ReportService(DocStore store, AuditService audit, Clock clock) {
        this.store = store;
        this.audit = audit;
        this.clock = clock;
    }

    private void checkPeriod(Instant from, Instant to) {
        if (!from.isBefore(to) || Duration.between(from, to).compareTo(MAX_PERIOD) > 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Período inválido (máximo de 366 dias).");
        }
    }

    public byte[] rides(AuthUser staff, Instant from, Instant to, String cityId) {
        checkPeriod(from, to);
        Query q = Query.of(Collections.RIDES).where("createdAt", Query.Op.GTE, from).where("createdAt", Query.Op.LT, to);
        if (cityId != null && !cityId.isBlank()) {
            q = Query.of(Collections.RIDES).eq("cityId", cityId).where("createdAt", Query.Op.GTE, from)
                    .where("createdAt", Query.Op.LT, to);
        }
        List<Ride> rides = store.query(q.orderBy("createdAt", Query.Direction.ASC).limit(MAX_ROWS), Ride.class);
        Map<String, String> cityNames = new HashMap<>();
        store.query(Query.of(Collections.CITIES), com.lucenadrive.geo.City.class)
                .forEach(c -> cityNames.put(c.id(), c.name() + " - " + c.state()));
        Csv csv = new Csv().row(List.of("Código", "Solicitada em", "Concluída em", "Cancelada em", "Status", "Cidade",
                "Categoria", "Distância (km)", "Duração (min)", "Valor total (R$)", "Desconto (R$)", "Valor final (R$)",
                "Comissão (R$)", "Ganho do motorista (R$)", "Pagamento", "Status do pagamento", "Cupom",
                "Motorista", "Placa", "Passageiro", "Cancelada por", "Motivo do cancelamento", "Taxa de cancelamento (R$)",
                "Avaliação do passageiro"));
        for (Ride r : rides) {
            FareBreakdown f = r.fare();
            csv.row(java.util.Arrays.asList(r.code(), r.createdAt(),
                    r.timeline() == null ? null : r.timeline().completedAt(),
                    r.timeline() == null ? null : r.timeline().cancelledAt(),
                    r.status(), cityNames.getOrDefault(r.cityId(), r.cityId()),
                    r.category() == null ? null : r.category().displayName(),
                    r.route() == null ? null : Csv.decimal(r.route().distanceMeters() / 1000.0, 2),
                    r.route() == null ? null : Csv.decimal(r.route().durationSeconds() / 60.0, 1),
                    f == null ? null : Csv.money(f.totalCents()), f == null ? null : Csv.money(f.discountCents()),
                    f == null ? null : Csv.money(f.finalCents()), f == null ? null : Csv.money(f.commissionCents()),
                    f == null ? null : Csv.money(f.driverEarningCents()), r.paymentMethod(), r.paymentStatus(),
                    r.couponCode(), r.driver() == null ? null : r.driver().name(), r.driver() == null ? null : r.driver().plate(),
                    r.passenger() == null ? null : r.passenger().name(),
                    r.cancellation() == null ? null : r.cancellation().byRole(),
                    r.cancellation() == null ? null : r.cancellation().reasonCode(),
                    r.cancellation() == null ? null : Csv.money(r.cancellation().feeCents()),
                    r.rating() == null ? null : r.rating().stars()));
        }
        audit.record(staff, "REPORT_EXPORTED", "report", "rides", Map.of(),
                Map.of("from", from.toString(), "to", to.toString(), "cityId", String.valueOf(cityId), "rows", rides.size()),
                "Exportação de relatório de corridas");
        return csv.bytes();
    }

    public byte[] ledger(AuthUser staff, Instant from, Instant to) {
        checkPeriod(from, to);
        List<LedgerEntry> entries = store.query(Query.of(Collections.LEDGER_ENTRIES).where("createdAt", Query.Op.GTE, from)
                .where("createdAt", Query.Op.LT, to).orderBy("createdAt", Query.Direction.ASC).limit(MAX_ROWS), LedgerEntry.class);
        Csv csv = new Csv().row(List.of("Data", "Tipo", "Conta", "Direção", "Valor (R$)", "Status", "Corrida", "Provedor",
                "Referência externa", "Estorno de", "Descrição", "ID do lançamento"));
        for (LedgerEntry e : entries) {
            csv.row(java.util.Arrays.asList(e.createdAt(), e.type(), e.account(), e.direction(), Csv.money(e.amountCents()),
                    e.status(), e.rideId(), e.provider(), e.externalRef(), e.reversalOf(), e.description(), e.id()));
        }
        audit.record(staff, "REPORT_EXPORTED", "report", "ledger", Map.of(),
                Map.of("from", from.toString(), "to", to.toString(), "rows", entries.size()),
                "Exportação de lançamentos financeiros");
        return csv.bytes();
    }

    public Instant now() {
        return Instant.now(clock);
    }
}
