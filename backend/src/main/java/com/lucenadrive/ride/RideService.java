package com.lucenadrive.ride;

import com.lucenadrive.geo.GeoPoint;
import com.lucenadrive.geo.GeoMath;
import com.lucenadrive.chat.ChatService;
import com.lucenadrive.common.error.ApiException;
import com.lucenadrive.common.error.ErrorCode;
import com.lucenadrive.common.web.Ids;
import com.lucenadrive.common.web.PageResponse;
import com.lucenadrive.config.LucenaProperties;
import com.lucenadrive.coupon.CouponRules;
import com.lucenadrive.coupon.CouponService;
import com.lucenadrive.driver.DriverLocation;
import com.lucenadrive.driver.DriverProfile;
import com.lucenadrive.fare.FareBreakdown;
import com.lucenadrive.fare.FareCalculator;
import com.lucenadrive.fare.FareConfig;
import com.lucenadrive.fare.FareService;
import com.lucenadrive.fare.VehicleCategory;
import com.lucenadrive.geo.City;
import com.lucenadrive.geo.GeoService;
import com.lucenadrive.geo.Place;
import com.lucenadrive.geo.RouteResult;
import com.lucenadrive.geo.RouteService;
import com.lucenadrive.notification.NotificationService;
import com.lucenadrive.notification.NotificationType;
import com.lucenadrive.passenger.PassengerProfile;
import com.lucenadrive.payment.PaymentMethod;
import com.lucenadrive.payment.PaymentProvider;
import com.lucenadrive.payment.PaymentStatus;
import com.lucenadrive.payment.RideSettlementService;
import com.lucenadrive.persistence.Collections;
import com.lucenadrive.persistence.DocStore;
import com.lucenadrive.persistence.Query;
import com.lucenadrive.security.AuthUser;
import com.lucenadrive.security.Role;
import com.lucenadrive.security.SecurityGuards;
import com.lucenadrive.user.AccountService;
import com.lucenadrive.user.User;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Ciclo de vida da corrida: cotação, solicitação, cancelamento, progresso e conclusão. */
@Service
public class RideService {

    private static final Logger log = LoggerFactory.getLogger(RideService.class);

    /** Tolerância após o aceite em que o passageiro cancela sem taxa. */
    static final Duration FREE_CANCEL_WINDOW = Duration.ofMinutes(2);

    public record QuoteView(String quoteId, VehicleCategory category, String categoryName, long totalCents,
                            long discountCents, long finalCents, boolean minimumApplied, long multiplierBp,
                            String couponCode, String couponMessage, long availableDrivers, Instant expiresAt) {
    }

    public record EstimateResult(String cityId, String cityName, Ride.RouteInfo route, boolean approximateRoute,
                                 List<QuoteView> quotes) {
    }

    private record IdempotencyRecord(String id, String rideId, Instant createdAt) {
    }

    private final DocStore store;
    private final GeoService geo;
    private final RouteService routes;
    private final FareService fares;
    private final CouponService coupons;
    private final ChatService chat;
    private final NotificationService notifications;
    private final RideEventService events;
    private final RideSettlementService settlement;
    private final PaymentProvider paymentProvider;
    private final DispatchService dispatch;
    private final SecurityGuards guards;
    private final LucenaProperties props;
    private final Clock clock;

    public RideService(DocStore store, GeoService geo, RouteService routes, FareService fares, CouponService coupons,
                       ChatService chat, NotificationService notifications, RideEventService events,
                       RideSettlementService settlement, PaymentProvider paymentProvider, DispatchService dispatch,
                       SecurityGuards guards, LucenaProperties props, Clock clock) {
        this.store = store;
        this.geo = geo;
        this.routes = routes;
        this.fares = fares;
        this.coupons = coupons;
        this.chat = chat;
        this.notifications = notifications;
        this.events = events;
        this.settlement = settlement;
        this.paymentProvider = paymentProvider;
        this.dispatch = dispatch;
        this.guards = guards;
        this.props = props;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ cotação

    public EstimateResult estimate(AuthUser auth, Place origin, Place destination, String couponCode) {
        requireActivePassenger(auth);
        City city = geo.validateTrip(origin.point(), destination.point());
        RouteResult route = routes.route(origin.point(), destination.point());
        Ride.RouteInfo routeInfo = new Ride.RouteInfo(route.distanceMeters(), route.durationSeconds(),
                route.encodedPolyline(), route.source());
        long regionBp = geo.regionMultiplierBp(city.id(), origin.point());
        Instant now = Instant.now(clock);
        Instant expiresAt = now.plus(props.dispatch().quoteTtl());
        long completed = store.get(Collections.PASSENGER_PROFILES, auth.uid(), PassengerProfile.class)
                .map(PassengerProfile::completedRides).orElse(0L);
        List<QuoteView> quotes = new ArrayList<>();
        for (VehicleCategory category : VehicleCategory.values()) {
            Optional<FareConfig> cfg = fares.current(city.id(), category);
            if (cfg.isEmpty()) {
                continue;
            }
            long multiplier = FareCalculator.effectiveMultiplier(cfg.get(), regionBp,
                    now.atZone(ZoneId.of(city.timezone() != null ? city.timezone() : "America/Sao_Paulo")));
            FareBreakdown base = FareCalculator.calculate(cfg.get(), route.distanceMeters(), route.durationSeconds(),
                    multiplier, 0);
            String normalizedCoupon = null;
            String couponMessage = null;
            long discount = 0;
            if (couponCode != null && !couponCode.isBlank()) {
                CouponRules.Evaluation eval = coupons.preview(couponCode, new CouponRules.Context(auth.uid(), city.id(),
                        category, base.totalCents(), now, 0, completed == 0));
                if (eval.valid()) {
                    discount = eval.discountCents();
                    normalizedCoupon = CouponRules.normalize(couponCode).orElse(null);
                    couponMessage = "Cupom aplicado.";
                } else {
                    couponMessage = eval.rejection();
                }
            }
            FareBreakdown fare = discount > 0
                    ? FareCalculator.calculate(cfg.get(), route.distanceMeters(), route.durationSeconds(), multiplier, discount)
                    : base;
            FareQuote quote = new FareQuote(Ids.newId(), auth.uid(), city.id(), category, origin, destination, routeInfo,
                    fare, multiplier, normalizedCoupon, couponMessage, expiresAt, now);
            store.set(Collections.FARE_QUOTES, quote.id(), quote);
            long available = store.count(Query.of(Collections.DRIVER_LOCATIONS).eq("available", true)
                    .eq("cityId", city.id()).where("categories", Query.Op.ARRAY_CONTAINS, category));
            quotes.add(new QuoteView(quote.id(), category, category.displayName(), fare.totalCents(), fare.discountCents(),
                    fare.finalCents(), fare.minimumApplied(), multiplier, normalizedCoupon, couponMessage, available,
                    expiresAt));
        }
        if (quotes.isEmpty()) {
            throw new ApiException(ErrorCode.FARE_NOT_CONFIGURED, "Nenhuma categoria disponível nesta cidade no momento.");
        }
        return new EstimateResult(city.id(), city.name(), routeInfo,
                route.source() == RouteResult.Source.STRAIGHT_LINE_ESTIMATE, quotes);
    }

    // ------------------------------------------------------------------ solicitação

    public RideView request(AuthUser auth, String quoteId, PaymentMethod method, String idempotencyKey) {
        User passenger = requireActivePassenger(auth);
        if (idempotencyKey == null || !idempotencyKey.matches("^[A-Za-z0-9-]{8,64}$")) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Cabeçalho Idempotency-Key obrigatório.");
        }
        if (method.requiresProvider() && !paymentProvider.configured()) {
            throw new ApiException(ErrorCode.INTEGRATION_NOT_CONFIGURED,
                    "Pagamento por " + method + " indisponível: provedor de pagamentos não configurado.");
        }
        String idemId = auth.uid() + "_" + idempotencyKey;
        record Outcome(Ride ride, boolean created) {
        }
        Outcome outcome = store.transaction(tx -> {
            Optional<IdempotencyRecord> idem = tx.get(Collections.IDEMPOTENCY_KEYS, idemId, IdempotencyRecord.class);
            if (idem.isPresent()) {
                Ride existing = tx.get(Collections.RIDES, idem.get().rideId(), Ride.class)
                        .orElseThrow(() -> ApiException.notFound("Corrida"));
                return new Outcome(existing, false);
            }
            FareQuote quote = tx.get(Collections.FARE_QUOTES, quoteId, FareQuote.class)
                    .filter(q -> q.passengerId().equals(auth.uid()))
                    .orElseThrow(() -> new ApiException(ErrorCode.QUOTE_EXPIRED, "Cotação não encontrada. Calcule novamente."));
            Instant now = Instant.now(clock);
            if (!now.isBefore(quote.expiresAt())) {
                throw new ApiException(ErrorCode.QUOTE_EXPIRED, "A cotação expirou. Calcule o preço novamente.");
            }
            List<Ride> active = tx.query(Query.of(Collections.RIDES).eq("passengerId", auth.uid())
                    .where("status", Query.Op.IN, RideStatus.ACTIVE).limit(1), Ride.class);
            if (!active.isEmpty()) {
                throw new ApiException(ErrorCode.ACTIVE_RIDE_EXISTS, "Você já possui uma corrida em andamento.",
                        Map.of("rideId", active.get(0).id()));
            }
            PassengerProfile paxProfile = tx.get(Collections.PASSENGER_PROFILES, auth.uid(), PassengerProfile.class)
                    .orElse(null);
            long completed = paxProfile == null ? 0L : paxProfile.completedRides();
            Double paxRating = paxProfile == null ? null : paxProfile.ratingAvgOrNull();
            FareBreakdown fare = quote.fare();
            CouponService.Reservation reservation = null;
            if (quote.couponCode() != null) {
                long totalBeforeDiscount = fare.totalCents();
                reservation = coupons.prepare(tx, quote.couponCode(), new CouponRules.Context(auth.uid(), quote.cityId(),
                        quote.category(), totalBeforeDiscount, now, 0, completed == 0));
                fare = withDiscount(fare, reservation.discountCents());
            }
            String rideId = Ids.newId();
            Ride ride = new Ride(rideId, Ids.rideCode(), auth.uid(), null, List.of(auth.uid()),
                    RideStatus.SEARCHING_DRIVER, quote.category(), quote.cityId(), quote.origin(), quote.destination(),
                    quote.route(), fare, quote.couponCode(), method, PaymentStatus.PENDING,
                    new Ride.PartySnapshot(auth.uid(), passenger.publicName(), passenger.photoUrl(), paxRating), null, null,
                    null, new Ride.Timeline(now, null, null, null, null, null, null), null,
                    new Ride.RatingState(Ride.RatingStatus.NOT_APPLICABLE, null), quote.id(), now, now,
                    new Ride.RatingState(Ride.RatingStatus.NOT_APPLICABLE, null));
            // ---- escritas
            tx.create(Collections.RIDES, rideId, ride);
            tx.set(Collections.RIDE_DISPATCH, rideId,
                    new RideDispatch(rideId, quote.cityId(), 0, List.of(), now, null, false));
            if (reservation != null) {
                coupons.apply(tx, reservation, rideId);
            }
            tx.create(Collections.IDEMPOTENCY_KEYS, idemId, new IdempotencyRecord(idemId, rideId, now));
            tx.delete(Collections.FARE_QUOTES, quote.id());
            events.recordInTx(tx, rideId, "RIDE_REQUESTED", null, RideStatus.REQUESTED, auth.uid(), ActorRole.PASSENGER,
                    Map.of("quoteId", quote.id(), "paymentMethod", method.name()));
            events.recordInTx(tx, rideId, "SEARCH_STARTED", RideStatus.REQUESTED, RideStatus.SEARCHING_DRIVER,
                    "system", ActorRole.SYSTEM, Map.of());
            return new Outcome(ride, true);
        });
        if (outcome.created()) {
            try {
                dispatch.dispatchNextBatch(outcome.ride().id());
            } catch (RuntimeException e) {
                // O agendador tentará novamente; a corrida permanece em SEARCHING_DRIVER.
                log.warn("initial_dispatch_failed ride={} err={}", outcome.ride().id(), e.toString());
            }
        }
        return RideView.of(store.get(Collections.RIDES, outcome.ride().id(), Ride.class).orElse(outcome.ride()),
                RideView.Viewer.PASSENGER);
    }

    static FareBreakdown withDiscount(FareBreakdown f, long discount) {
        long d = Math.min(discount, f.totalCents());
        return new FareBreakdown(f.fareConfigId(), f.fareConfigVersion(), f.baseFareCents(), f.distanceCents(),
                f.timeCents(), f.subtotalCents(), f.multiplierBp(), f.minimumApplied(), f.totalCents(), d,
                f.totalCents() - d, f.commissionBp(), f.commissionCents(), f.driverEarningCents(), f.cancellationFeeCents());
    }

    // ------------------------------------------------------------------ consulta

    public RideView get(AuthUser auth, String rideId) {
        Ride ride = store.get(Collections.RIDES, rideId, Ride.class).orElseThrow(() -> ApiException.notFound("Corrida"));
        return RideView.of(ride, viewerOf(auth, ride));
    }

    public Optional<RideView> active(AuthUser auth) {
        if (auth.hasRole(Role.DRIVER)) {
            Optional<DriverLocation> loc = store.get(Collections.DRIVER_LOCATIONS, auth.uid(), DriverLocation.class);
            if (loc.isPresent() && loc.get().currentRideId() != null) {
                return store.get(Collections.RIDES, loc.get().currentRideId(), Ride.class)
                        .map(r -> RideView.of(r, RideView.Viewer.DRIVER));
            }
        }
        return store.query(Query.of(Collections.RIDES).eq("passengerId", auth.uid())
                        .where("status", Query.Op.IN, RideStatus.ACTIVE).limit(1), Ride.class)
                .stream().findFirst().map(r -> RideView.of(r, RideView.Viewer.PASSENGER));
    }

    /** Corrida concluída mais recente aguardando avaliação (sobrevive ao fechamento do app). */
    public Optional<RideView> pendingRating(AuthUser auth) {
        Instant since = Instant.now(clock).minus(props.ratings().pendingWindow());
        return store.query(Query.of(Collections.RIDES).eq("passengerId", auth.uid())
                        .eq("rating.status", Ride.RatingStatus.PENDING)
                        .where("timeline.completedAt", Query.Op.GTE, since)
                        .orderBy("timeline.completedAt", Query.Direction.DESC).limit(1), Ride.class)
                .stream().findFirst().map(r -> RideView.of(r, RideView.Viewer.PASSENGER));
    }

    public PageResponse<RideView> history(AuthUser auth, boolean asDriver, RideStatus status, Long cursor, int limit) {
        int size = Math.min(Math.max(limit, 1), 50);
        Query q = Query.of(Collections.RIDES).eq(asDriver ? "driverId" : "passengerId", auth.uid());
        if (status != null) {
            q.eq("status", status);
        }
        q.orderBy("createdAt", Query.Direction.DESC).limit(size);
        if (cursor != null) {
            q.startAfter(cursor);
        }
        List<RideView> items = store.query(q, Ride.class).stream()
                .map(r -> RideView.of(r, asDriver ? RideView.Viewer.DRIVER : RideView.Viewer.PASSENGER)).toList();
        return PageResponse.of(items, size, v -> v.createdAt().toEpochMilli());
    }

    RideView.Viewer viewerOf(AuthUser auth, Ride ride) {
        if (auth.uid().equals(ride.passengerId())) {
            return RideView.Viewer.PASSENGER;
        }
        if (auth.uid().equals(ride.driverId())) {
            return RideView.Viewer.DRIVER;
        }
        if (auth.isStaff() && auth.mfaVerified()) {
            return RideView.Viewer.STAFF;
        }
        throw ApiException.forbidden("Você não participa desta corrida.");
    }

    // ------------------------------------------------------------------ cancelamento

    public RideView cancelByPassenger(AuthUser auth, String rideId, String reasonCode, String reasonText) {
        return cancel(auth, rideId, ActorRole.PASSENGER, reasonCode, reasonText);
    }

    public RideView cancelByDriver(AuthUser auth, String rideId, String reasonCode, String reasonText) {
        return cancel(auth, rideId, ActorRole.DRIVER, reasonCode, reasonText);
    }

    public RideView cancelByStaff(AuthUser auth, String rideId, String reasonText) {
        if (reasonText == null || reasonText.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Motivo obrigatório.");
        }
        return cancel(auth, rideId, ActorRole.STAFF, "STAFF", reasonText);
    }

    /** Cancelamento automático por falta de motoristas (agendador de despacho). */
    public void cancelBySystem(String rideId, String reasonCode, String reasonText) {
        cancel(null, rideId, ActorRole.SYSTEM, reasonCode, reasonText);
    }

    private RideView cancel(AuthUser auth, String rideId, ActorRole actor, String reasonCode, String reasonText) {
        String actorId = auth == null ? "system" : auth.uid();
        Ride result = store.transaction(tx -> {
            Ride ride = tx.get(Collections.RIDES, rideId, Ride.class).orElseThrow(() -> ApiException.notFound("Corrida"));
            if (actor == ActorRole.PASSENGER && !actorId.equals(ride.passengerId())
                    || actor == ActorRole.DRIVER && !actorId.equals(ride.driverId())) {
                throw ApiException.forbidden("Você não participa desta corrida.");
            }
            RideStateMachine.assertTransition(ride.status(), RideStatus.CANCELLED, actor);
            Instant now = Instant.now(clock);
            long fee = 0;
            if (actor == ActorRole.PASSENGER && ride.fare() != null && ride.timeline().acceptedAt() != null) {
                boolean afterGrace = ride.timeline().acceptedAt().plus(FREE_CANCEL_WINDOW).isBefore(now);
                if (ride.status() == RideStatus.DRIVER_ARRIVED || afterGrace) {
                    fee = ride.fare().cancellationFeeCents();
                }
            }
            // ---- leituras
            List<RideOffer> pending = tx.query(Query.of(Collections.RIDE_OFFERS).eq("rideId", rideId)
                    .eq("status", RideOffer.Status.PENDING), RideOffer.class);
            Optional<DriverLocation> driverLoc = ride.driverId() == null ? Optional.empty()
                    : tx.get(Collections.DRIVER_LOCATIONS, ride.driverId(), DriverLocation.class);
            Optional<CouponService.Release> release = ride.couponCode() == null ? Optional.empty()
                    : coupons.prepareRelease(tx, ride.couponCode(), rideId);
            Optional<RideDispatch> disp = tx.get(Collections.RIDE_DISPATCH, rideId, RideDispatch.class);
            Optional<PassengerProfile> pp = actor == ActorRole.PASSENGER
                    ? tx.get(Collections.PASSENGER_PROFILES, ride.passengerId(), PassengerProfile.class) : Optional.empty();
            Optional<DriverProfile> dp = actor == ActorRole.DRIVER
                    ? tx.get(Collections.DRIVER_PROFILES, ride.driverId(), DriverProfile.class) : Optional.empty();
            var feeDrafts = settlement.planCancellationFee(tx, ride, fee);
            // ---- escritas
            Ride cancelled = ride.toBuilder()
                    .status(RideStatus.CANCELLED, now)
                    .cancellation(new Ride.Cancellation(actorId, actor, reasonCode,
                            reasonText == null ? null : reasonText.substring(0, Math.min(reasonText.length(), 300)), fee))
                    .paymentStatus(PaymentStatus.NOT_CHARGED)
                    .rating(new Ride.RatingState(Ride.RatingStatus.NOT_APPLICABLE, null))
                    .build();
            tx.set(Collections.RIDES, rideId, cancelled);
            pending.forEach(o -> tx.set(Collections.RIDE_OFFERS, o.id(), o.withStatus(RideOffer.Status.SUPERSEDED, now)));
            driverLoc.filter(l -> rideId.equals(l.currentRideId()))
                    .ifPresent(l -> tx.set(Collections.DRIVER_LOCATIONS, l.driverId(), l.withRide(null, now)));
            release.ifPresent(r -> coupons.applyRelease(tx, r));
            disp.ifPresent(d -> tx.set(Collections.RIDE_DISPATCH, rideId, new RideDispatch(d.rideId(), d.cityId(),
                    d.batches(), d.offeredDriverIds(), d.searchStartedAt(), d.lastBatchAt(), true)));
            pp.ifPresent(p -> tx.set(Collections.PASSENGER_PROFILES, p.id(), p.afterCancelledRide(now)));
            dp.ifPresent(p -> tx.set(Collections.DRIVER_PROFILES, p.id(),
                    p.toBuilder().cancelledRides(p.cancelledRides() + 1).updatedAt(now).build()));
            settlement.applyDrafts(tx, feeDrafts);
            events.recordInTx(tx, rideId, "RIDE_CANCELLED", ride.status(), RideStatus.CANCELLED, actorId, actor,
                    Map.of("reasonCode", String.valueOf(reasonCode), "feeCents", fee));
            return cancelled;
        });
        chat.systemMessage(rideId, "CANCELLED", "Corrida cancelada.");
        chat.closeForWriting(rideId);
        String msg = switch (actor) {
            case PASSENGER -> "O passageiro cancelou a corrida.";
            case DRIVER -> "O motorista cancelou a corrida. Você pode solicitar novamente.";
            case SYSTEM -> "Não encontramos motoristas disponíveis. Tente novamente em instantes.";
            case STAFF -> "A corrida foi cancelada pela equipe Lucena Drive.";
        };
        for (String uid : result.participantIds()) {
            if (!uid.equals(actorId)) {
                notifications.notify(uid, NotificationType.RIDE_CANCELLED, "Corrida cancelada", msg, Map.of("rideId", rideId));
            }
        }
        RideView.Viewer viewer = actor == ActorRole.DRIVER ? RideView.Viewer.DRIVER
                : actor == ActorRole.PASSENGER ? RideView.Viewer.PASSENGER : RideView.Viewer.STAFF;
        return RideView.of(result, viewer);
    }

    // ------------------------------------------------------------------ progresso (motorista)

    public RideView driverAdvance(AuthUser auth, String rideId, RideStatus target) {
        if (target == RideStatus.COMPLETED) {
            return complete(auth, rideId);
        }
        if (target != RideStatus.DRIVER_ARRIVING && target != RideStatus.DRIVER_ARRIVED && target != RideStatus.IN_PROGRESS) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "Ação inválida.");
        }
        Ride updated = store.transaction(tx -> {
            Ride ride = tx.get(Collections.RIDES, rideId, Ride.class).orElseThrow(() -> ApiException.notFound("Corrida"));
            if (!auth.uid().equals(ride.driverId())) {
                throw ApiException.forbidden("Você não é o motorista desta corrida.");
            }
            RideStateMachine.assertTransition(ride.status(), target, ActorRole.DRIVER);
            Instant now = Instant.now(clock);
            if (target == RideStatus.DRIVER_ARRIVED) {
                assertNearPickup(ride, now);
            }
            Ride next = ride.toBuilder().status(target, now).etaSeconds(target == RideStatus.DRIVER_ARRIVED ? 0L : ride.etaSeconds()).build();
            tx.set(Collections.RIDES, rideId, next);
            events.recordInTx(tx, rideId, "STATUS_" + target.name(), ride.status(), target, auth.uid(), ActorRole.DRIVER,
                    Map.of());
            return next;
        });
        switch (target) {
            case DRIVER_ARRIVING -> chat.systemMessage(rideId, "DRIVER_ARRIVING", "Motorista a caminho do embarque.");
            case DRIVER_ARRIVED -> {
                chat.systemMessage(rideId, "DRIVER_ARRIVED", "Motorista chegou ao local de embarque.");
                notifications.notify(updated.passengerId(), NotificationType.DRIVER_ARRIVED, "Seu motorista chegou",
                        "Confira a placa antes de embarcar.", Map.of("rideId", rideId));
            }
            case IN_PROGRESS -> {
                chat.systemMessage(rideId, "IN_PROGRESS", "Viagem iniciada.");
                notifications.notify(updated.passengerId(), NotificationType.RIDE_STARTED, "Viagem iniciada",
                        "Boa viagem!", Map.of("rideId", rideId));
            }
            default -> {
            }
        }
        return RideView.of(updated, RideView.Viewer.DRIVER);
    }

    /** "Cheguei" só com posição recente (≤ 2 min) a até maxArrivalDistanceMeters do embarque. */
    private void assertNearPickup(Ride ride, Instant now) {
        int max = props.dispatch().maxArrivalDistanceMeters();
        if (max <= 0) {
            return;
        }
        Ride.LivePosition pos = ride.driverPosition();
        if (pos == null || pos.updatedAt() == null || pos.updatedAt().isBefore(now.minus(Duration.ofMinutes(2)))) {
            throw new ApiException(ErrorCode.TOO_FAR_FROM_PICKUP,
                    "Sua localização não está atualizada. Ative o GPS e tente novamente.");
        }
        double meters = GeoMath.haversineMeters(new GeoPoint(pos.lat(), pos.lng()), ride.origin().point());
        if (meters > max) {
            throw new ApiException(ErrorCode.TOO_FAR_FROM_PICKUP,
                    "Você está a " + Math.round(meters) + " m do embarque. Aproxime-se (até " + max + " m) para avisar que chegou.");
        }
    }

    private RideView complete(AuthUser auth, String rideId) {
        Ride completed = store.transaction(tx -> {
            Ride ride = tx.get(Collections.RIDES, rideId, Ride.class).orElseThrow(() -> ApiException.notFound("Corrida"));
            if (!auth.uid().equals(ride.driverId())) {
                throw ApiException.forbidden("Você não é o motorista desta corrida.");
            }
            RideStateMachine.assertTransition(ride.status(), RideStatus.COMPLETED, ActorRole.DRIVER);
            Instant now = Instant.now(clock);
            // ---- leituras
            Optional<DriverProfile> dp = tx.get(Collections.DRIVER_PROFILES, ride.driverId(), DriverProfile.class);
            Optional<PassengerProfile> pp = tx.get(Collections.PASSENGER_PROFILES, ride.passengerId(), PassengerProfile.class);
            Optional<DriverLocation> loc = tx.get(Collections.DRIVER_LOCATIONS, ride.driverId(), DriverLocation.class);
            var redemption = ride.couponCode() == null ? Optional.<com.lucenadrive.coupon.CouponRedemption>empty()
                    : coupons.findRedemption(tx, ride.couponCode(), rideId);
            RideSettlementService.Plan plan = settlement.planCompletion(tx, ride);
            // ---- escritas
            Ride next = ride.toBuilder().status(RideStatus.COMPLETED, now)
                    .paymentStatus(plan.rideStatus())
                    .etaSeconds(0L)
                    .rating(new Ride.RatingState(Ride.RatingStatus.PENDING, null))
                    .driverRating(new Ride.RatingState(Ride.RatingStatus.PENDING, null))
                    .build();
            tx.set(Collections.RIDES, rideId, next);
            dp.ifPresent(p -> tx.set(Collections.DRIVER_PROFILES, p.id(),
                    p.toBuilder().completedRides(p.completedRides() + 1).updatedAt(now).build()));
            pp.ifPresent(p -> tx.set(Collections.PASSENGER_PROFILES, p.id(), p.afterCompletedRide(ride.destination(), now)));
            loc.filter(l -> rideId.equals(l.currentRideId()))
                    .ifPresent(l -> tx.set(Collections.DRIVER_LOCATIONS, l.driverId(), l.withRide(null, now)));
            redemption.filter(r -> r.status() == com.lucenadrive.coupon.CouponRedemption.Status.RESERVED)
                    .ifPresent(r -> coupons.confirm(tx, r));
            settlement.apply(tx, plan);
            events.recordInTx(tx, rideId, "RIDE_COMPLETED", ride.status(), RideStatus.COMPLETED, auth.uid(),
                    ActorRole.DRIVER, Map.of("finalCents", ride.fare().finalCents()));
            return next;
        });
        settlement.requestCharge(completed);
        chat.systemMessage(rideId, "COMPLETED", "Viagem finalizada. Obrigado por viajar com o Lucena Drive!");
        chat.closeForWriting(rideId);
        notifications.notify(completed.passengerId(), NotificationType.RATING_PENDING, "Como foi sua viagem?",
                "Avalie sua experiência com o Lucena Drive.", Map.of("rideId", rideId));
        return RideView.of(completed, RideView.Viewer.DRIVER);
    }

    // ------------------------------------------------------------------ apoio

    User requireActivePassenger(AuthUser auth) {
        if (!auth.hasRole(Role.PASSENGER)) {
            throw new ApiException(ErrorCode.PROFILE_NOT_FOUND, "Complete seu cadastro de passageiro.");
        }
        guards.requireVerifiedEmail(auth);
        User u = store.get(Collections.USERS, auth.uid(), User.class)
                .orElseThrow(() -> new ApiException(ErrorCode.PROFILE_NOT_FOUND, "Cadastro não encontrado."));
        AccountService.ensureActive(u);
        return u;
    }

    /** Usado pelo despacho ao aceitar (snapshot de exibição para o passageiro). */
    static Ride.DriverSnapshot snapshot(User u, DriverProfile p, com.lucenadrive.driver.Vehicle v) {
        return new Ride.DriverSnapshot(u.id(), u.publicName(), u.photoUrl(), p.ratingAvg(), p.ratingCount(), v.id(),
                v.make(), v.model(), v.color(), v.displayPlate(), v.year());
    }
}
