package com.lucenadrive.rating;

import com.lucenadrive.audit.AuditService;
import com.lucenadrive.chat.MessageSanitizer;
import com.lucenadrive.common.error.ApiException;
import com.lucenadrive.common.error.ErrorCode;
import com.lucenadrive.config.LucenaProperties;
import com.lucenadrive.driver.DriverProfile;
import com.lucenadrive.passenger.PassengerProfile;
import com.lucenadrive.persistence.Collections;
import com.lucenadrive.persistence.DocStore;
import com.lucenadrive.persistence.Query;
import com.lucenadrive.ride.Ride;
import com.lucenadrive.ride.RideStatus;
import com.lucenadrive.security.AuthUser;
import com.lucenadrive.support.SupportService;
import com.lucenadrive.support.SupportTicket;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Avaliações: passageiro → motorista e, de forma recíproca, motorista → passageiro. Gravação da nota, atualização do agregado do motorista
 * e marcação da corrida acontecem numa única transação: a média é sempre consistente e uma
 * segunda avaliação para a mesma corrida é impossível (ID determinístico + verificação de estado).
 */
@Service
public class RatingService {

    public static final Set<String> ALLOWED_TAGS = Set.of(
            "DIRECAO_SEGURA", "CARRO_LIMPO", "PONTUALIDADE", "CORDIALIDADE", "BOA_ROTA", "BOA_CONVERSA",
            "DIRECAO_PERIGOSA", "CARRO_SUJO", "ATRASO", "ROTA_RUIM", "CONDUTA_INADEQUADA", "MUSICA_ALTA");

    /** Categorias usadas pelo motorista ao avaliar o passageiro. */
    public static final Set<String> PASSENGER_TAGS = Set.of(
            "EDUCADO", "PONTUAL", "RESPEITOSO", "BOA_CONVERSA",
            "ATRASO_EMBARQUE", "CONDUTA_INADEQUADA", "SUJOU_VEICULO", "ENDERECO_ERRADO");

    public static final int MAX_COMMENT = 500;

    public record DriverRatingSummary(String driverId, double average, long count, Map<String, Long> starCounts,
                                      List<PublicComment> recentComments) {
    }

    public record PublicComment(int stars, String comment, List<String> tags, Instant createdAt) {
    }

    private final DocStore store;
    private final AuditService audit;
    private final SupportService support;
    private final LucenaProperties props;
    private final Clock clock;

    public RatingService(DocStore store, AuditService audit, SupportService support, LucenaProperties props, Clock clock) {
        this.store = store;
        this.audit = audit;
        this.support = support;
        this.props = props;
        this.clock = clock;
    }

    public Rating submit(AuthUser passenger, String rideId, int stars, String rawComment, List<String> tags) {
        List<String> cleanTags = validTags(stars, tags, ALLOWED_TAGS);
        String finalComment = cleanComment(rawComment);
        String ratingId = Rating.idFor(rideId, Rating.RaterRole.PASSENGER);
        return store.transaction(tx -> {
            Ride ride = tx.get(Collections.RIDES, rideId, Ride.class).orElseThrow(() -> ApiException.notFound("Corrida"));
            if (!passenger.uid().equals(ride.passengerId())) {
                throw ApiException.forbidden("Somente o passageiro da corrida pode avaliá-la.");
            }
            if (ride.status() != RideStatus.COMPLETED) {
                throw new ApiException(ErrorCode.RATING_NOT_ALLOWED, "Somente corridas concluídas podem ser avaliadas.");
            }
            if (tx.get(Collections.RATINGS, ratingId, Rating.class).isPresent()
                    || ride.rating() == null || ride.rating().status() == Ride.RatingStatus.SUBMITTED) {
                throw new ApiException(ErrorCode.RATING_ALREADY_SUBMITTED, "Esta corrida já foi avaliada.");
            }
            if (ride.rating().status() != Ride.RatingStatus.PENDING) {
                throw new ApiException(ErrorCode.RATING_NOT_ALLOWED, "Avaliação indisponível para esta corrida.");
            }
            Instant now = Instant.now(clock);
            if (ride.timeline().completedAt() != null
                    && ride.timeline().completedAt().plus(props.ratings().pendingWindow()).isBefore(now)) {
                throw new ApiException(ErrorCode.RATING_NOT_ALLOWED, "O prazo para avaliar esta corrida terminou.");
            }
            DriverProfile driver = tx.get(Collections.DRIVER_PROFILES, ride.driverId(), DriverProfile.class)
                    .orElseThrow(() -> ApiException.notFound("Motorista"));
            // ---- escritas
            Rating rating = new Rating(ratingId, rideId, passenger.uid(), Rating.RaterRole.PASSENGER, ride.driverId(),
                    Rating.RaterRole.DRIVER, stars, finalComment, cleanTags, Rating.Status.VISIBLE, false, List.of(), now, now);
            tx.create(Collections.RATINGS, ratingId, rating);
            Map<String, Long> starsMap = new HashMap<>(driver.starCounts() == null ? Map.of() : driver.starCounts());
            starsMap.merge(String.valueOf(stars), 1L, Long::sum);
            tx.set(Collections.DRIVER_PROFILES, driver.id(), driver.toBuilder()
                    .rating(driver.ratingSum() + stars, driver.ratingCount() + 1, starsMap).updatedAt(now).build());
            tx.set(Collections.RIDES, rideId, ride.toBuilder()
                    .rating(new Ride.RatingState(Ride.RatingStatus.SUBMITTED, stars)).updatedAt(now).build());
            return rating;
        });
    }

    private static List<String> validTags(int stars, List<String> tags, Set<String> allowed) {
        if (stars < 1 || stars > 5) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A avaliação deve ter de 1 a 5 estrelas.");
        }
        List<String> cleanTags = tags == null ? List.of() : tags.stream().distinct().toList();
        if (!allowed.containsAll(cleanTags) || cleanTags.size() > 6) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Categorias de avaliação inválidas.");
        }
        return cleanTags;
    }

    private static String cleanComment(String rawComment) {
        String comment = rawComment == null ? null : MessageSanitizer.sanitize(rawComment);
        if (comment != null && comment.isEmpty()) {
            return null;
        }
        if (comment != null && comment.length() > MAX_COMMENT) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Comentário excede " + MAX_COMMENT + " caracteres.");
        }
        return comment;
    }

    /**
     * Avaliação recíproca: o motorista avalia o passageiro (uma vez por corrida, dentro do mesmo prazo).
     * A nota individual não é gravada na corrida (o passageiro lê esse documento); só o agregado do
     * passageiro é atualizado, na mesma transação.
     */
    public Rating submitByDriver(AuthUser driver, String rideId, int stars, String rawComment, List<String> tags) {
        List<String> cleanTags = validTags(stars, tags, PASSENGER_TAGS);
        String finalComment = cleanComment(rawComment);
        String ratingId = Rating.idFor(rideId, Rating.RaterRole.DRIVER);
        return store.transaction(tx -> {
            Ride ride = tx.get(Collections.RIDES, rideId, Ride.class).orElseThrow(() -> ApiException.notFound("Corrida"));
            if (!driver.uid().equals(ride.driverId())) {
                throw ApiException.forbidden("Somente o motorista da corrida pode avaliar o passageiro.");
            }
            if (ride.status() != RideStatus.COMPLETED) {
                throw new ApiException(ErrorCode.RATING_NOT_ALLOWED, "Somente corridas concluídas podem ser avaliadas.");
            }
            if (tx.get(Collections.RATINGS, ratingId, Rating.class).isPresent() || (ride.driverRating() != null
                    && ride.driverRating().status() == Ride.RatingStatus.SUBMITTED)) {
                throw new ApiException(ErrorCode.RATING_ALREADY_SUBMITTED, "Você já avaliou este passageiro.");
            }
            if (ride.driverRating() == null || ride.driverRating().status() != Ride.RatingStatus.PENDING) {
                throw new ApiException(ErrorCode.RATING_NOT_ALLOWED, "Avaliação indisponível para esta corrida.");
            }
            Instant now = Instant.now(clock);
            if (ride.timeline().completedAt() != null
                    && ride.timeline().completedAt().plus(props.ratings().pendingWindow()).isBefore(now)) {
                throw new ApiException(ErrorCode.RATING_NOT_ALLOWED, "O prazo para avaliar esta corrida terminou.");
            }
            PassengerProfile pax = tx.get(Collections.PASSENGER_PROFILES, ride.passengerId(), PassengerProfile.class)
                    .orElseThrow(() -> ApiException.notFound("Passageiro"));
            // ---- escritas
            Rating rating = new Rating(ratingId, rideId, driver.uid(), Rating.RaterRole.DRIVER, ride.passengerId(),
                    Rating.RaterRole.PASSENGER, stars, finalComment, cleanTags, Rating.Status.VISIBLE, false, List.of(),
                    now, now);
            tx.create(Collections.RATINGS, ratingId, rating);
            tx.set(Collections.PASSENGER_PROFILES, pax.id(), pax.withRating(pax.ratingSum() + stars,
                    pax.ratingCount() + 1, now));
            tx.set(Collections.RIDES, rideId, ride.toBuilder()
                    .driverRating(new Ride.RatingState(Ride.RatingStatus.SUBMITTED, null)).updatedAt(now).build());
            return rating;
        });
    }

    public record PassengerRatingSummary(Double average, long count) {
    }

    /** O passageiro vê apenas a própria média (sem notas individuais nem autores). */
    public PassengerRatingSummary passengerSummary(String passengerId) {
        return store.get(Collections.PASSENGER_PROFILES, passengerId, PassengerProfile.class)
                .map(p -> new PassengerRatingSummary(p.ratingAvgOrNull(), p.ratingCount()))
                .orElse(new PassengerRatingSummary(null, 0));
    }

    public void skip(AuthUser passenger, String rideId) {
        if (!props.ratings().allowSkip()) {
            throw new ApiException(ErrorCode.RATING_NOT_ALLOWED, "A avaliação é obrigatória.");
        }
        store.transaction(tx -> {
            Ride ride = tx.get(Collections.RIDES, rideId, Ride.class).orElseThrow(() -> ApiException.notFound("Corrida"));
            if (!passenger.uid().equals(ride.passengerId())) {
                throw ApiException.forbidden("Corrida de outro passageiro.");
            }
            if (ride.rating() == null || ride.rating().status() != Ride.RatingStatus.PENDING) {
                throw new ApiException(ErrorCode.RATING_NOT_ALLOWED, "Não há avaliação pendente.");
            }
            tx.set(Collections.RIDES, rideId, ride.toBuilder()
                    .rating(new Ride.RatingState(Ride.RatingStatus.SKIPPED, null)).updatedAt(Instant.now(clock)).build());
            return null;
        });
    }

    /** Resumo público: média, contagem e comentários visíveis (sem identificar avaliadores). */
    public DriverRatingSummary driverSummary(String driverId) {
        DriverProfile p = store.get(Collections.DRIVER_PROFILES, driverId, DriverProfile.class)
                .orElseThrow(() -> ApiException.notFound("Motorista"));
        List<PublicComment> comments = store.query(Query.of(Collections.RATINGS).eq("rateeId", driverId)
                        .eq("rateeRole", Rating.RaterRole.DRIVER).eq("status", Rating.Status.VISIBLE).orderBy("createdAt", Query.Direction.DESC).limit(20), Rating.class)
                .stream().filter(r -> r.comment() != null)
                .map(r -> new PublicComment(r.stars(), r.comment(), r.tags(), r.createdAt())).toList();
        return new DriverRatingSummary(driverId, p.ratingAvg(), p.ratingCount(),
                p.starCounts() == null ? Map.of() : p.starCounts(), comments);
    }

    /** Motorista contesta uma avaliação recebida: abre chamado de revisão (não altera a nota). */
    public SupportTicket dispute(AuthUser driver, String ratingId, String reason) {
        Rating r = store.get(Collections.RATINGS, ratingId, Rating.class).orElseThrow(() -> ApiException.notFound("Avaliação"));
        if (!driver.uid().equals(r.rateeId()) || r.rateeRole() != Rating.RaterRole.DRIVER) {
            throw ApiException.forbidden("Avaliação de outro motorista.");
        }
        store.update(Collections.RATINGS, ratingId, Map.of("reported", true));
        return support.open(driver, SupportTicket.Category.RATING_DISPUTE, r.rideId(),
                "Contestação de avaliação", reason, Map.of("ratingId", ratingId));
    }

    // -------------------------------------------------------- moderação (equipe)

    public List<Rating> list(Rating.Status status, Boolean reported, String rateeId, int limit) {
        Query q = Query.of(Collections.RATINGS);
        if (status != null) {
            q.eq("status", status);
        }
        if (reported != null) {
            q.eq("reported", reported);
        }
        if (rateeId != null) {
            q.eq("rateeId", rateeId);
        }
        return store.query(q.orderBy("createdAt", Query.Direction.DESC).limit(Math.min(limit, 200)), Rating.class);
    }

    public Map<String, Object> platformStats() {
        long count = store.count(Query.of(Collections.RATINGS).where("status", Query.Op.IN,
                List.of(Rating.Status.VISIBLE, Rating.Status.COMMENT_HIDDEN)));
        long sum = store.sum(Query.of(Collections.RATINGS).where("status", Query.Op.IN,
                List.of(Rating.Status.VISIBLE, Rating.Status.COMMENT_HIDDEN)), "stars");
        Map<String, Long> dist = new HashMap<>();
        for (int s = 1; s <= 5; s++) {
            dist.put(String.valueOf(s), store.count(Query.of(Collections.RATINGS).eq("stars", s)
                    .where("status", Query.Op.IN, List.of(Rating.Status.VISIBLE, Rating.Status.COMMENT_HIDDEN))));
        }
        long reported = store.count(Query.of(Collections.RATINGS).eq("reported", true));
        return Map.of("count", count, "average", count == 0 ? 0.0 : Math.round((double) sum / count * 100) / 100.0,
                "distribution", dist, "reported", reported);
    }

    /**
     * Ações de moderação. Ocultar comentário não altera a nota. Invalidar remove a nota da média
     * (somente com justificativa) e é reversível por RESTORE. Tudo auditado.
     */
    public Rating moderate(AuthUser staff, String ratingId, String action, String reason) {
        if (reason == null || reason.trim().length() < 10) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Justificativa obrigatória (mín. 10 caracteres).");
        }
        return store.transaction(tx -> {
            Rating r = tx.get(Collections.RATINGS, ratingId, Rating.class).orElseThrow(() -> ApiException.notFound("Avaliação"));
            boolean aboutDriver = r.rateeRole() != Rating.RaterRole.PASSENGER;
            DriverProfile driver = aboutDriver ? tx.get(Collections.DRIVER_PROFILES, r.rateeId(), DriverProfile.class)
                    .orElseThrow(() -> ApiException.notFound("Motorista")) : null;
            PassengerProfile pax = aboutDriver ? null : tx.get(Collections.PASSENGER_PROFILES, r.rateeId(),
                    PassengerProfile.class).orElseThrow(() -> ApiException.notFound("Passageiro"));
            Instant now = Instant.now(clock);
            Rating next;
            int delta = 0;
            switch (action) {
                case "HIDE_COMMENT" -> {
                    if (r.status() != Rating.Status.VISIBLE) {
                        throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "Comentário já moderado.");
                    }
                    next = r.moderate(Rating.Status.COMMENT_HIDDEN, action, staff.uid(), reason, now, r.reported());
                }
                case "INVALIDATE" -> {
                    if (r.status() == Rating.Status.INVALIDATED) {
                        throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "Avaliação já invalidada.");
                    }
                    next = r.moderate(Rating.Status.INVALIDATED, action, staff.uid(), reason, now, r.reported());
                    delta = -1;
                }
                case "RESTORE" -> {
                    if (r.status() == Rating.Status.VISIBLE) {
                        throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "Avaliação já visível.");
                    }
                    delta = r.status() == Rating.Status.INVALIDATED ? 1 : 0;
                    next = r.moderate(Rating.Status.VISIBLE, action, staff.uid(), reason, now, r.reported());
                }
                case "DISMISS_REPORT" -> next = r.moderate(r.status(), action, staff.uid(), reason, now, false);
                default -> throw new ApiException(ErrorCode.VALIDATION_FAILED, "Ação de moderação desconhecida.");
            }
            tx.set(Collections.RATINGS, ratingId, next);
            if (delta != 0 && !aboutDriver) {
                tx.set(Collections.PASSENGER_PROFILES, pax.id(), pax.withRating(pax.ratingSum() + (long) delta * r.stars(),
                        pax.ratingCount() + delta, now));
            } else if (delta != 0) {
                Map<String, Long> starsMap = new HashMap<>(driver.starCounts() == null ? Map.of() : driver.starCounts());
                starsMap.merge(String.valueOf(r.stars()), (long) delta, Long::sum);
                tx.set(Collections.DRIVER_PROFILES, driver.id(), driver.toBuilder()
                        .rating(driver.ratingSum() + (long) delta * r.stars(), driver.ratingCount() + delta, starsMap)
                        .updatedAt(now).build());
            }
            audit.recordInTx(tx, staff, "RATING_" + action, "rating", ratingId,
                    Map.of("status", r.status().name(), "stars", r.stars()),
                    Map.of("status", next.status().name(), "stars", r.stars()), reason);
            return next;
        });
    }
}
