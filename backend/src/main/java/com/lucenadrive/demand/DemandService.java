package com.lucenadrive.demand;

import com.lucenadrive.audit.AuditService;
import com.lucenadrive.common.error.ApiException;
import com.lucenadrive.common.error.ErrorCode;
import com.lucenadrive.driver.DriverLocation;
import com.lucenadrive.geo.City;
import com.lucenadrive.persistence.Collections;
import com.lucenadrive.persistence.DocMapper;
import com.lucenadrive.persistence.DocStore;
import com.lucenadrive.persistence.Query;
import com.lucenadrive.ride.FareQuote;
import com.lucenadrive.scheduling.SchedulerLock;
import com.lucenadrive.security.AuthUser;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Demanda × oferta por cidade: preço dinâmico (opcional) e mapa de calor agregado para motoristas e equipe.
 * O mapa de calor usa apenas os pontos de embarque das cotações recentes, agrupados em quadras de ~550 m, e só
 * exibe quadras com pelo menos {@link #MIN_CELL_COUNT} pedidos — sem identificar passageiros.
 */
@Service
public class DemandService {

    private static final Logger log = LoggerFactory.getLogger(DemandService.class);
    static final double CELL_DEG = 0.005;
    static final int MIN_CELL_COUNT = 2;
    static final Duration STATE_TTL = Duration.ofMinutes(5);
    static final Duration HEAT_WINDOW = Duration.ofMinutes(30);

    public record HeatCell(double lat, double lng, long count) {
    }

    public record CityDemand(DemandPolicy policy, DemandState state) {
    }

    private final DocStore store;
    private final AuditService audit;
    private final SchedulerLock lock;
    private final Clock clock;

    public DemandService(DocStore store, AuditService audit, SchedulerLock lock, Clock clock) {
        this.store = store;
        this.audit = audit;
        this.clock = clock;
        this.lock = lock;
    }

    public DemandPolicy policy(String cityId) {
        return store.get(Collections.DEMAND_POLICIES, cityId, DemandPolicy.class).orElse(DemandPolicy.defaults(cityId));
    }

    public CityDemand view(String cityId) {
        return new CityDemand(policy(cityId), store.get(Collections.DEMAND_STATES, cityId, DemandState.class).orElse(null));
    }

    public DemandPolicy update(AuthUser admin, String cityId, boolean enabled, List<DemandPolicy.Step> steps, long maxBp,
                               int windowMinutes, String reason) {
        if (reason == null || reason.trim().length() < 10) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Justificativa obrigatória (mín. 10 caracteres).");
        }
        if (maxBp < 10_000 || maxBp > 30_000) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "O teto deve ficar entre 1,0× e 3,0×.");
        }
        if (windowMinutes < 5 || windowMinutes > 60) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A janela de cálculo deve ficar entre 5 e 60 minutos.");
        }
        List<DemandPolicy.Step> clean = new ArrayList<>(steps == null ? List.of() : steps);
        if (clean.isEmpty() || clean.size() > 6 || clean.stream().anyMatch(s -> s.minRatio() < 1.0 || s.minRatio() > 50
                || s.multiplierBp() < 10_000 || s.multiplierBp() > maxBp)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "Degraus inválidos: de 1 a 6, razão mínima entre 1 e 50 e multiplicador entre 1,0× e o teto.");
        }
        clean.sort(Comparator.comparingDouble(DemandPolicy.Step::minRatio));
        Instant now = Instant.now(clock);
        DemandPolicy next = new DemandPolicy(cityId, enabled, clean, maxBp, windowMinutes, admin.uid(), now);
        return store.transaction(tx -> {
            if (tx.get(Collections.CITIES, cityId, City.class).isEmpty()) {
                throw ApiException.notFound("Cidade");
            }
            DemandPolicy prev = tx.get(Collections.DEMAND_POLICIES, cityId, DemandPolicy.class).orElse(null);
            tx.set(Collections.DEMAND_POLICIES, cityId, next);
            audit.recordInTx(tx, admin, "DEMAND_POLICY_UPDATED", "demandPolicy", cityId,
                    prev == null ? null : DocMapper.toMap(prev), DocMapper.toMap(next), reason.trim());
            return next;
        });
    }

    /**
     * Multiplicador dinâmico vigente para a cidade (1,0× se desligado, sem cálculo recente ou sem demanda).
     * O estado é recalculado pelo agendador; cotações apenas leem (sem custo extra por pedido).
     */
    public long currentMultiplierBp(String cityId) {
        DemandPolicy p = policy(cityId);
        if (!p.enabled()) {
            return 10_000;
        }
        return store.get(Collections.DEMAND_STATES, cityId, DemandState.class)
                .filter(s -> s.updatedAt() != null && s.updatedAt().isAfter(Instant.now(clock).minus(STATE_TTL)))
                .map(DemandState::multiplierBp).orElse(10_000L);
    }

    /** Recalcula demanda × oferta de uma cidade (passageiros distintos pedindo preço ÷ motoristas disponíveis). */
    public DemandState recompute(String cityId) {
        DemandPolicy p = policy(cityId);
        Instant now = Instant.now(clock);
        Set<String> passengers = new HashSet<>();
        for (String[] r : recentRequests(cityId, now.minus(Duration.ofMinutes(p.windowMinutes())))) {
            passengers.add(r[0]);
        }
        long supply = store.count(Query.of(Collections.DRIVER_LOCATIONS).eq("available", true).eq("cityId", cityId));
        double ratio = passengers.isEmpty() ? 0 : passengers.size() / (double) Math.max(1, supply);
        DemandState state = new DemandState(cityId, passengers.size(), supply, Math.round(ratio * 100) / 100.0,
                p.multiplierFor(ratio), now);
        store.set(Collections.DEMAND_STATES, cityId, state);
        return state;
    }

    @Scheduled(fixedDelayString = "${lucena.demand.tick-ms:60000}")
    public void tick() {
        if (!lock.tryAcquire("demand", Duration.ofSeconds(90))) {
            return;
        }
        // Cotações vencidas há mais de 1 dia não servem para nada: são apagadas (até 500 por rodada).
        for (FareQuote q : store.query(Query.of(Collections.FARE_QUOTES)
                .where("createdAt", Query.Op.LT, Instant.now(clock).minus(Duration.ofDays(1))).limit(500), FareQuote.class)) {
            store.delete(Collections.FARE_QUOTES, q.id());
        }
        for (DemandPolicy p : store.query(Query.of(Collections.DEMAND_POLICIES).eq("enabled", true).limit(200), DemandPolicy.class)) {
            try {
                recompute(p.cityId());
            } catch (RuntimeException e) {
                log.warn("demand_recompute_failed city={} err={}", p.cityId(), e.toString());
            }
        }
    }

    /**
     * Cotações recentes da cidade. Filtra só por data no banco (índice simples, sem índice composto a publicar)
     * e separa a cidade em memória — o volume da janela é pequeno.
     */
    private List<FareQuote> recentQuotes(String cityId, Instant since) {
        return store.query(Query.of(Collections.FARE_QUOTES).where("createdAt", Query.Op.GTE, since).limit(10_000),
                FareQuote.class).stream().filter(q -> cityId.equals(q.cityId())).toList();
    }

    /** Pedidos recentes: cotações ainda guardadas + corridas solicitadas (a cotação é apagada ao solicitar). */
    private List<String[]> recentRequests(String cityId, Instant since) {
        List<String[]> out = new ArrayList<>();
        for (FareQuote q : recentQuotes(cityId, since)) {
            out.add(new String[] {q.passengerId(), String.valueOf(q.origin().lat()), String.valueOf(q.origin().lng())});
        }
        for (com.lucenadrive.ride.Ride r : store.query(Query.of(Collections.RIDES).where("createdAt", Query.Op.GTE, since)
                .limit(10_000), com.lucenadrive.ride.Ride.class)) {
            if (cityId.equals(r.cityId())) {
                out.add(new String[] {r.passengerId(), String.valueOf(r.origin().lat()), String.valueOf(r.origin().lng())});
            }
        }
        return out;
    }

    /** Mapa de calor da cidade (últimos 30 min), em quadras agregadas; quadras com poucos pedidos são omitidas. */
    public List<HeatCell> heatmap(String cityId) {
        Instant since = Instant.now(clock).minus(HEAT_WINDOW);
        Map<String, Set<String>> cells = new HashMap<>();
        Map<String, double[]> centers = new HashMap<>();
        for (String[] r : recentRequests(cityId, since)) {
            double clat = Math.floor(Double.parseDouble(r[1]) / CELL_DEG) * CELL_DEG + CELL_DEG / 2;
            double clng = Math.floor(Double.parseDouble(r[2]) / CELL_DEG) * CELL_DEG + CELL_DEG / 2;
            String key = String.format(java.util.Locale.ROOT, "%.4f,%.4f", clat, clng);
            cells.computeIfAbsent(key, k -> new HashSet<>()).add(r[0]);
            centers.putIfAbsent(key, new double[] {clat, clng});
        }
        List<HeatCell> out = new ArrayList<>();
        cells.forEach((k, set) -> {
            if (set.size() >= MIN_CELL_COUNT) {
                double[] c = centers.get(k);
                out.add(new HeatCell(Math.round(c[0] * 1e5) / 1e5, Math.round(c[1] * 1e5) / 1e5, set.size()));
            }
        });
        out.sort(Comparator.comparingLong(HeatCell::count).reversed());
        return out;
    }

    /** Cidade do motorista (pela posição operacional) para o mapa de calor do app do motorista. */
    public String driverCity(String driverId) {
        return store.get(Collections.DRIVER_LOCATIONS, driverId, DriverLocation.class).map(DriverLocation::cityId)
                .orElseThrow(() -> new ApiException(ErrorCode.DRIVER_NOT_ELIGIBLE, "Fique online para ver a demanda."));
    }
}
