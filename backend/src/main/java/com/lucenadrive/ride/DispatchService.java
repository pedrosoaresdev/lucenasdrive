package com.lucenadrive.ride;

import com.lucenadrive.chat.ChatService;
import com.lucenadrive.common.error.ApiException;
import com.lucenadrive.common.error.ErrorCode;
import com.lucenadrive.config.LucenaProperties;
import com.lucenadrive.driver.DriverLocation;
import com.lucenadrive.driver.DriverProfile;
import com.lucenadrive.driver.DriverStatus;
import com.lucenadrive.driver.Vehicle;
import com.lucenadrive.geo.GeoMath;
import com.lucenadrive.geo.GeoPoint;
import com.lucenadrive.notification.NotificationService;
import com.lucenadrive.notification.NotificationType;
import com.lucenadrive.persistence.Collections;
import com.lucenadrive.persistence.DocStore;
import com.lucenadrive.persistence.Query;
import com.lucenadrive.security.AuthUser;
import com.lucenadrive.user.User;
import com.lucenadrive.user.UserStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Despacho de corridas: seleciona motoristas elegíveis mais próximos, envia ofertas em lotes com
 * expiração e resolve o aceite com transação — apenas um motorista consegue aceitar.
 *
 * <p>O agendador roda em cada instância; em múltiplas instâncias as transações garantem
 * consistência (ofertas/aceites não duplicam), mas recomenda-se um lock distribuído para evitar
 * trabalho redundante (ver docs/DEPLOY.md).
 */
@Service
public class DispatchService {

    private static final Logger log = LoggerFactory.getLogger(DispatchService.class);
    private static final Duration STALE_LOCATION = Duration.ofMinutes(2);

    private final DocStore store;
    private final LucenaProperties props;
    private final NotificationService notifications;
    private final ChatService chat;
    private final RideEventService events;
    private final RideService rides;
    private final com.lucenadrive.scheduling.SchedulerLock lock;
    private final Clock clock;

    public DispatchService(DocStore store, LucenaProperties props, NotificationService notifications, ChatService chat,
                           RideEventService events, @Lazy RideService rides,
                           com.lucenadrive.scheduling.SchedulerLock lock, Clock clock) {
        this.store = store;
        this.props = props;
        this.notifications = notifications;
        this.chat = chat;
        this.events = events;
        this.rides = rides;
        this.lock = lock;
        this.clock = clock;
    }

    record Candidate(DriverLocation location, long distanceMeters, Long etaSeconds) {

        Candidate withEta(Long eta) {
            return new Candidate(location, distanceMeters, eta);
        }
    }

    /** Quantos motoristas livres mais próximos (em linha reta) entram no cálculo de ETA pelas ruas. */
    static final int ETA_POOL = 10;

    private com.lucenadrive.geo.RouteService routes;

    @org.springframework.beans.factory.annotation.Autowired
    void setRouteService(com.lucenadrive.geo.RouteService routes) {
        this.routes = routes;
    }

    /**
     * Ordena os candidatos pelo MENOR tempo de chegada ao embarque pelas ruas (Google/OSRM, uma consulta em matriz).
     * Sem serviço de rotas, ou se ele falhar, mantém a ordem por distância (estimativa). Sem rota = fim da fila.
     */
    List<Candidate> rankByEta(List<Candidate> nearest, GeoPoint pickup) {
        if (nearest.isEmpty() || routes == null || !routes.available()) {
            return nearest;
        }
        try {
            List<Long> etas = routes.etaSeconds(nearest.stream()
                    .map(c -> new GeoPoint(c.location().lat(), c.location().lng())).toList(), pickup);
            List<Candidate> withEta = new ArrayList<>();
            for (int i = 0; i < nearest.size(); i++) {
                withEta.add(nearest.get(i).withEta(i < etas.size() ? etas.get(i) : null));
            }
            withEta.sort(Comparator.comparing((Candidate c) -> c.etaSeconds() == null ? Long.MAX_VALUE : c.etaSeconds())
                    .thenComparingLong(Candidate::distanceMeters));
            return withEta;
        } catch (RuntimeException e) {
            log.warn("dispatch_eta_failed err={}", e.toString());
            return nearest;
        }
    }

    /** Envia o próximo lote de ofertas. Retorna quantas ofertas foram criadas. */
    public int dispatchNextBatch(String rideId) {
        Ride ride = store.get(Collections.RIDES, rideId, Ride.class).orElse(null);
        RideDispatch disp = store.get(Collections.RIDE_DISPATCH, rideId, RideDispatch.class).orElse(null);
        if (ride == null || disp == null || ride.status() != RideStatus.SEARCHING_DRIVER || disp.finished()) {
            return 0;
        }
        Instant now = Instant.now(clock);
        GeoPoint pickup = ride.origin().point();
        List<Candidate> candidates = store.query(Query.of(Collections.DRIVER_LOCATIONS)
                        .eq("available", true).eq("cityId", ride.cityId())
                        .where("categories", Query.Op.ARRAY_CONTAINS, ride.category()), DriverLocation.class)
                .stream()
                .filter(l -> l.lat() != null && l.lng() != null)
                .filter(l -> l.updatedAt() != null && l.updatedAt().isAfter(now.minus(STALE_LOCATION)))
                .filter(l -> !disp.excludes(l.driverId()))
                .filter(l -> !l.driverId().equals(ride.passengerId()))
                .map(l -> new Candidate(l, Math.round(GeoMath.haversineMeters(pickup, new GeoPoint(l.lat(), l.lng()))), null))
                .filter(c -> c.distanceMeters() <= props.dispatch().radiusKm() * 1000)
                .sorted(Comparator.comparingLong(Candidate::distanceMeters))
                .toList();
        // Livres de verdade: sem corrida (available) e sem outra oferta pendente.
        List<Candidate> free = new ArrayList<>();
        for (Candidate c : candidates) {
            if (free.size() >= ETA_POOL) {
                break;
            }
            boolean busyWithOffer = store.count(Query.of(Collections.RIDE_OFFERS).eq("driverId", c.location().driverId())
                    .eq("status", RideOffer.Status.PENDING)) > 0;
            if (!busyWithOffer) {
                free.add(c);
            }
        }
        List<Candidate> chosen = rankByEta(free, pickup).stream().limit(props.dispatch().batchSize()).toList();
        log.debug("dispatch ride={} candidates={} free={} chosen={} etas={}", rideId, candidates.size(), free.size(), chosen.size(),
                chosen.stream().map(Candidate::etaSeconds).toList());
        Instant expiresAt = now.plus(props.dispatch().offerTimeout());
        List<RideOffer> created = store.transaction(tx -> {
            Ride current = tx.get(Collections.RIDES, rideId, Ride.class).orElse(null);
            RideDispatch d = tx.get(Collections.RIDE_DISPATCH, rideId, RideDispatch.class).orElse(null);
            if (current == null || d == null || current.status() != RideStatus.SEARCHING_DRIVER || d.finished()) {
                return List.<RideOffer>of();
            }
            List<RideOffer> offers = new ArrayList<>();
            List<String> offered = new ArrayList<>(d.offeredDriverIds());
            for (Candidate c : chosen) {
                String driverId = c.location().driverId();
                RideOffer offer = new RideOffer(RideOffer.idFor(rideId, driverId), rideId, driverId,
                        RideOffer.Status.PENDING, current.category(), current.origin().address(),
                        current.destination().address(), current.origin().lat(), current.origin().lng(),
                        c.distanceMeters(), current.route().distanceMeters(), current.route().durationSeconds(),
                        current.fare().driverEarningCents(), current.passenger().name(), current.passenger().rating(),
                        expiresAt, now, null, c.etaSeconds());
                offers.add(offer);
                offered.add(driverId);
            }
            offers.forEach(o -> tx.set(Collections.RIDE_OFFERS, o.id(), o));
            tx.set(Collections.RIDE_DISPATCH, rideId, new RideDispatch(rideId, d.cityId(), d.batches() + 1, offered,
                    d.searchStartedAt(), now, false, d.redispatches(), d.excludedDriverIds()));
            return offers;
        });
        for (RideOffer o : created) {
            notifications.notify(o.driverId(), NotificationType.RIDE_OFFER, "Nova corrida disponível",
                    "Embarque a " + (o.distanceToPickupMeters() / 100) / 10.0 + " km. Responda em instantes.",
                    Map.of("rideId", rideId, "offerId", o.id()));
        }
        return created.size();
    }

    /**
     * Aceite da oferta. Toda a verificação ocorre numa transação sobre o documento da corrida:
     * se dois motoristas aceitarem ao mesmo tempo, o Firestore reexecuta a transação do segundo,
     * que encontra a corrida já ACCEPTED e recebe RIDE_ALREADY_ACCEPTED.
     */
    public RideView accept(AuthUser driver, String rideId) {
        String offerId = RideOffer.idFor(rideId, driver.uid());
        Ride accepted = store.transaction(tx -> {
            Ride ride = tx.get(Collections.RIDES, rideId, Ride.class).orElseThrow(() -> ApiException.notFound("Corrida"));
            RideOffer offer = tx.get(Collections.RIDE_OFFERS, offerId, RideOffer.class)
                    .orElseThrow(() -> ApiException.forbidden("Esta oferta não foi enviada para você."));
            Instant now = Instant.now(clock);
            if (ride.status() != RideStatus.SEARCHING_DRIVER) {
                throw new ApiException(ErrorCode.RIDE_ALREADY_ACCEPTED, "Esta corrida não está mais disponível.");
            }
            if (offer.status() != RideOffer.Status.PENDING || !now.isBefore(offer.expiresAt())) {
                throw new ApiException(ErrorCode.OFFER_EXPIRED, "A oferta expirou.");
            }
            DriverLocation loc = tx.get(Collections.DRIVER_LOCATIONS, driver.uid(), DriverLocation.class)
                    .orElseThrow(() -> new ApiException(ErrorCode.DRIVER_NOT_ELIGIBLE, "Fique online para aceitar corridas."));
            if (!loc.online() || loc.currentRideId() != null) {
                throw new ApiException(ErrorCode.DRIVER_NOT_ELIGIBLE, "Você está offline ou já possui uma corrida.");
            }
            DriverProfile profile = tx.get(Collections.DRIVER_PROFILES, driver.uid(), DriverProfile.class)
                    .filter(p -> p.status() == DriverStatus.APPROVED)
                    .orElseThrow(() -> new ApiException(ErrorCode.DRIVER_NOT_ELIGIBLE, "Cadastro não aprovado."));
            Vehicle vehicle = tx.get(Collections.VEHICLES, loc.vehicleId(), Vehicle.class)
                    .filter(v -> v.status() == Vehicle.Status.APPROVED)
                    .orElseThrow(() -> new ApiException(ErrorCode.DRIVER_NOT_ELIGIBLE, "Veículo não aprovado."));
            User user = tx.get(Collections.USERS, driver.uid(), User.class)
                    .filter(u -> u.status() == UserStatus.ACTIVE)
                    .orElseThrow(() -> new ApiException(ErrorCode.ACCOUNT_SUSPENDED, "Conta indisponível."));
            List<RideOffer> others = tx.query(Query.of(Collections.RIDE_OFFERS).eq("rideId", rideId)
                    .eq("status", RideOffer.Status.PENDING), RideOffer.class);
            Optional<RideDispatch> disp = tx.get(Collections.RIDE_DISPATCH, rideId, RideDispatch.class);
            // ---- escritas
            Long eta = null;
            if (loc.lat() != null && loc.lng() != null) {
                double meters = GeoMath.haversineMeters(new GeoPoint(loc.lat(), loc.lng()), ride.origin().point());
                eta = Math.max(60, Math.round(meters * 1.35 / (25_000.0 / 3600.0)));
            }
            Ride next = ride.toBuilder()
                    .driver(driver.uid(), RideService.snapshot(user, profile, vehicle))
                    .status(RideStatus.ACCEPTED, now)
                    .driverPosition(loc.lat() == null ? null : new Ride.LivePosition(loc.lat(), loc.lng(), loc.heading(), now))
                    .etaSeconds(eta)
                    .build();
            tx.set(Collections.RIDES, rideId, next);
            tx.set(Collections.RIDE_OFFERS, offerId, offer.withStatus(RideOffer.Status.ACCEPTED, now));
            others.stream().filter(o -> !o.id().equals(offerId))
                    .forEach(o -> tx.set(Collections.RIDE_OFFERS, o.id(), o.withStatus(RideOffer.Status.SUPERSEDED, now)));
            tx.set(Collections.DRIVER_LOCATIONS, driver.uid(), loc.withRide(rideId, now));
            disp.ifPresent(d -> tx.set(Collections.RIDE_DISPATCH, rideId, d.finish()));
            chat.openInTx(tx, next, now);
            events.recordInTx(tx, rideId, "RIDE_ACCEPTED", RideStatus.SEARCHING_DRIVER, RideStatus.ACCEPTED,
                    driver.uid(), ActorRole.DRIVER, Map.of("vehicleId", vehicle.id()));
            return next;
        });
        chat.systemMessage(rideId, "ACCEPTED", "Motorista aceitou a corrida.");
        notifications.notify(accepted.passengerId(), NotificationType.RIDE_ACCEPTED, "Motorista a caminho",
                accepted.driver().name() + " aceitou sua corrida.", Map.of("rideId", rideId));
        return RideView.of(accepted, RideView.Viewer.DRIVER);
    }

    public void decline(AuthUser driver, String rideId) {
        String offerId = RideOffer.idFor(rideId, driver.uid());
        boolean shouldRedispatch = store.transaction(tx -> {
            RideOffer offer = tx.get(Collections.RIDE_OFFERS, offerId, RideOffer.class)
                    .orElseThrow(() -> ApiException.notFound("Oferta"));
            if (offer.status() != RideOffer.Status.PENDING) {
                return false;
            }
            List<RideOffer> stillPending = tx.query(Query.of(Collections.RIDE_OFFERS).eq("rideId", rideId)
                    .eq("status", RideOffer.Status.PENDING), RideOffer.class);
            tx.set(Collections.RIDE_OFFERS, offerId, offer.withStatus(RideOffer.Status.DECLINED, Instant.now(clock)));
            return stillPending.size() <= 1;
        });
        if (shouldRedispatch) {
            dispatchNextBatch(rideId);
        }
    }

    public List<RideOffer> pendingOffers(String driverId) {
        Instant now = Instant.now(clock);
        return store.query(Query.of(Collections.RIDE_OFFERS).eq("driverId", driverId)
                        .eq("status", RideOffer.Status.PENDING), RideOffer.class)
                .stream().filter(o -> now.isBefore(o.expiresAt())).toList();
    }

    /** Expira ofertas, envia novos lotes e encerra buscas sem sucesso. */
    @Scheduled(fixedDelayString = "${lucena.dispatch.tick-ms:3000}")
    public void tick() {
        if (!lock.tryAcquire("dispatch", Duration.ofSeconds(30))) {
            return;
        }
        try {
            Instant now = Instant.now(clock);
            for (RideOffer o : store.query(Query.of(Collections.RIDE_OFFERS).eq("status", RideOffer.Status.PENDING)
                    .where("expiresAt", Query.Op.LTE, now).limit(200), RideOffer.class)) {
                store.transaction(tx -> {
                    tx.get(Collections.RIDE_OFFERS, o.id(), RideOffer.class)
                            .filter(cur -> cur.status() == RideOffer.Status.PENDING)
                            .ifPresent(cur -> tx.set(Collections.RIDE_OFFERS, cur.id(),
                                    cur.withStatus(RideOffer.Status.EXPIRED, now)));
                    return null;
                });
            }
            for (Ride ride : store.query(Query.of(Collections.RIDES).eq("status", RideStatus.SEARCHING_DRIVER).limit(200),
                    Ride.class)) {
                processSearching(ride, now);
            }
        } catch (RuntimeException e) {
            log.warn("dispatch_tick_failed err={}", e.toString());
        }
    }

    private void processSearching(Ride ride, Instant now) {
        RideDispatch d = store.get(Collections.RIDE_DISPATCH, ride.id(), RideDispatch.class).orElse(null);
        if (d == null || d.finished()) {
            return;
        }
        long pending = store.count(Query.of(Collections.RIDE_OFFERS).eq("rideId", ride.id())
                .eq("status", RideOffer.Status.PENDING));
        if (pending > 0) {
            return;
        }
        boolean timedOut = d.searchStartedAt().plus(props.dispatch().maxSearchTime()).isBefore(now);
        if (timedOut || d.batches() >= props.dispatch().maxBatches()) {
            try {
                rides.cancelBySystem(ride.id(), "NO_DRIVERS_AVAILABLE", "Nenhum motorista disponível");
            } catch (ApiException e) {
                log.debug("cancel_no_driver_skipped ride={} reason={}", ride.id(), e.getMessage());
            }
            return;
        }
        dispatchNextBatch(ride.id());
    }
}
