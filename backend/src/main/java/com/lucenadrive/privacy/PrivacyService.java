package com.lucenadrive.privacy;

import com.lucenadrive.audit.AuditService;
import com.lucenadrive.chat.ChatMessage;
import com.lucenadrive.chat.RideChat;
import com.lucenadrive.common.crypto.FieldEncryptor;
import com.lucenadrive.common.error.ApiException;
import com.lucenadrive.common.error.ErrorCode;
import com.lucenadrive.common.web.Ids;
import com.lucenadrive.driver.DriverProfile;
import com.lucenadrive.driver.Plates;
import com.lucenadrive.driver.Vehicle;
import com.lucenadrive.integration.AuthAdmin;
import com.lucenadrive.integration.ObjectStorage;
import com.lucenadrive.ledger.LedgerEntry;
import com.lucenadrive.ledger.LedgerService;
import com.lucenadrive.notification.Notification;
import com.lucenadrive.notification.PushToken;
import com.lucenadrive.passenger.PassengerProfile;
import com.lucenadrive.payout.PayoutRequest;
import com.lucenadrive.payout.PixKey;
import com.lucenadrive.persistence.Collections;
import com.lucenadrive.persistence.DocStore;
import com.lucenadrive.persistence.Query;
import com.lucenadrive.rating.Rating;
import com.lucenadrive.ride.Ride;
import com.lucenadrive.ride.RideStatus;
import com.lucenadrive.ride.RideView;
import com.lucenadrive.security.AuthUser;
import com.lucenadrive.security.RateLimiter;
import com.lucenadrive.security.Role;
import com.lucenadrive.security.SecurityGuards;
import com.lucenadrive.support.SupportTicket;
import com.lucenadrive.support.TicketMessage;
import com.lucenadrive.user.NotificationPreferences;
import com.lucenadrive.user.PrivacyRequest;
import com.lucenadrive.user.User;
import com.lucenadrive.user.UserPrivate;
import com.lucenadrive.user.UserStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Direitos do titular (LGPD art. 18): exportação dos próprios dados (acesso/portabilidade) e
 * anonimização da conta, executada pela equipe a partir de uma solicitação de exclusão.
 *
 * Registros financeiros (lançamentos, transações, repasses) e trilhas de auditoria são mantidos
 * pelo prazo legal (art. 16, I) referenciando apenas o identificador pseudônimo da conta.
 */
@Service
public class PrivacyService {

    private static final Logger log = LoggerFactory.getLogger(PrivacyService.class);
    private static final int LIMIT = 500;
    static final String REMOVED = "Removido (LGPD)";

    private final DocStore store;
    private final AuditService audit;
    private final SecurityGuards guards;
    private final RateLimiter limiter;
    private final LedgerService ledger;
    private final ObjectStorage storage;
    private final AuthAdmin authAdmin;
    private final FieldEncryptor encryptor;
    private final Clock clock;

    public PrivacyService(DocStore store, AuditService audit, SecurityGuards guards, RateLimiter limiter,
                          LedgerService ledger, ObjectStorage storage, AuthAdmin authAdmin,
                          FieldEncryptor encryptor, Clock clock) {
        this.store = store;
        this.audit = audit;
        this.guards = guards;
        this.limiter = limiter;
        this.ledger = ledger;
        this.storage = storage;
        this.authAdmin = authAdmin;
        this.encryptor = encryptor;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ exportação

    /** Exporta os dados do próprio titular. Exige login recente; no máximo 3 exportações por hora. */
    public Map<String, Object> export(AuthUser auth) {
        guards.requireRecentLogin(auth);
        if (!limiter.tryAcquire("export:" + auth.uid(), 3, 3_600_000)) {
            throw new ApiException(ErrorCode.RATE_LIMITED, "Limite de exportações atingido. Tente mais tarde.");
        }
        User user = store.get(Collections.USERS, auth.uid(), User.class)
                .orElseThrow(() -> new ApiException(ErrorCode.PROFILE_NOT_FOUND, "Cadastro não encontrado."));
        String uid = user.id();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generatedAt", Instant.now(clock));
        out.put("format", "lucena-drive-data-export/v1");
        out.put("notice", "Cópia dos seus dados pessoais tratados pelo Lucena Drive (LGPD art. 18, II e V). "
                + "O CPF completo é armazenado criptografado e é exibido aqui mascarado por segurança.");

        Map<String, Object> account = new LinkedHashMap<>();
        account.put("id", uid);
        account.put("email", user.email());
        account.put("fullName", user.fullName());
        account.put("phoneNumber", user.phoneNumber());
        account.put("photoPath", user.photoUrl());
        account.put("roles", user.roles());
        account.put("status", user.status());
        account.put("cpfMasked", user.cpfMasked());
        account.put("birthDate", store.get(Collections.USER_PRIVATE, uid, UserPrivate.class)
                .map(UserPrivate::birthDate).orElse(null));
        account.put("notificationPreferences", user.notificationPreferences());
        account.put("consent", Map.of("termsVersion", String.valueOf(user.termsVersion()),
                "privacyVersion", String.valueOf(user.privacyVersion()),
                "acceptedAt", String.valueOf(user.consentAt())));
        account.put("createdAt", user.createdAt());
        out.put("account", account);

        store.get(Collections.PASSENGER_PROFILES, uid, PassengerProfile.class).ifPresent(p -> out.put("passenger",
                Map.of("favorites", p.favorites() == null ? List.of() : p.favorites(),
                        "recentDestinations", p.recentDestinations() == null ? List.of() : p.recentDestinations(),
                        "completedRides", p.completedRides(), "cancelledRides", p.cancelledRides(),
                        "ratingReceivedAverage", String.valueOf(p.ratingAvgOrNull()),
                        "ratingReceivedCount", p.ratingCount())));

        store.get(Collections.DRIVER_PROFILES, uid, DriverProfile.class).ifPresent(d -> {
            Map<String, Object> driver = new LinkedHashMap<>();
            driver.put("status", d.status());
            driver.put("cnhNumberMasked", d.cnhNumberMasked());
            driver.put("cnhCategory", d.cnhCategory());
            driver.put("cnhExpiresOn", d.cnhExpiresOn());
            driver.put("cityId", d.cityId());
            driver.put("documents", d.documents() == null ? List.of() : d.documents().stream()
                    .map(doc -> Map.of("type", doc.type(), "status", doc.status(),
                            "expiresOn", String.valueOf(doc.expiresOn()), "uploadedAt", String.valueOf(doc.uploadedAt())))
                    .toList());
            driver.put("rating", Map.of("average", d.ratingAvg(), "count", d.ratingCount()));
            driver.put("vehicles", store.query(Query.of(Collections.VEHICLES).eq("driverId", uid).limit(50), Vehicle.class));
            driver.put("pixKey", store.get(Collections.PIX_KEYS, uid, PixKey.class)
                    .map(k -> Map.of("type", k.keyType(), "masked", k.keyMasked())).orElse(null));
            driver.put("payouts", store.query(Query.of(Collections.PAYOUT_REQUESTS).eq("ownerId", uid)
                    .orderBy("createdAt", Query.Direction.DESC).limit(LIMIT), PayoutRequest.class));
            driver.put("ledger", all(Query.of(Collections.LEDGER_ENTRIES).eq("account", LedgerEntry.driverAccount(uid)),
                    LedgerEntry.class));
            out.put("driver", driver);
        });

        List<Ride> asPassenger = store.query(Query.of(Collections.RIDES).eq("passengerId", uid)
                .orderBy("createdAt", Query.Direction.DESC).limit(LIMIT), Ride.class);
        List<Ride> asDriver = store.query(Query.of(Collections.RIDES).eq("driverId", uid)
                .orderBy("createdAt", Query.Direction.DESC).limit(LIMIT), Ride.class);
        out.put("ridesAsPassenger", asPassenger.stream().map(r -> RideView.of(r, RideView.Viewer.PASSENGER)).toList());
        out.put("ridesAsDriver", asDriver.stream().map(r -> RideView.of(r, RideView.Viewer.DRIVER)).toList());

        List<Map<String, Object>> chat = new ArrayList<>();
        for (Ride r : concat(asPassenger, asDriver)) {
            if (store.get(Collections.RIDE_CHATS, r.id(), RideChat.class).isEmpty()) {
                continue; // já removido pela política de retenção
            }
            for (ChatMessage m : store.query(Query.of(Collections.messages(r.id())).eq("senderId", uid).limit(LIMIT),
                    ChatMessage.class)) {
                chat.add(Map.of("rideId", r.id(), "text", String.valueOf(m.text()), "sentAt", m.createdAt()));
            }
        }
        out.put("chatMessagesSent", chat);

        out.put("ratingsGiven", store.query(Query.of(Collections.RATINGS).eq("raterId", uid).limit(LIMIT), Rating.class)
                .stream().map(r -> Map.of("rideId", r.rideId(), "stars", r.stars(), "comment", String.valueOf(r.comment()),
                        "tags", r.tags() == null ? List.of() : r.tags(), "createdAt", r.createdAt())).toList());
        // Avaliações recebidas sem identificar quem avaliou.
        out.put("ratingsReceived", store.query(Query.of(Collections.RATINGS).eq("rateeId", uid).limit(LIMIT), Rating.class)
                .stream().filter(Rating::countsInAverage)
                .map(r -> Map.of("rideId", r.rideId(), "stars", r.stars(), "as", r.rateeRole(),
                        "createdAt", r.createdAt())).toList());

        List<Map<String, Object>> tickets = new ArrayList<>();
        for (SupportTicket t : store.query(Query.of(Collections.SUPPORT_TICKETS).eq("userId", uid).limit(LIMIT),
                SupportTicket.class)) {
            List<Map<String, Object>> msgs = store.query(Query.of(Collections.ticketMessages(t.id()))
                    .orderBy("createdAt", Query.Direction.ASC).limit(LIMIT), TicketMessage.class).stream()
                    .map(m -> Map.<String, Object>of("fromStaff", m.fromStaff(), "text", String.valueOf(m.text()),
                            "createdAt", m.createdAt())).toList();
            tickets.add(Map.of("id", t.id(), "category", t.category(), "status", t.status(),
                    "subject", String.valueOf(t.subject()), "description", String.valueOf(t.description()),
                    "attachments", t.attachmentPaths() == null ? 0 : t.attachmentPaths().size(),
                    "messages", msgs, "createdAt", t.createdAt()));
        }
        out.put("supportTickets", tickets);
        out.put("notifications", store.query(Query.of(Collections.NOTIFICATIONS).eq("userId", uid)
                .orderBy("createdAt", Query.Direction.DESC).limit(200), Notification.class));
        out.put("locationSharing", store.get(Collections.LOCATION_CONSENTS, uid,
                com.lucenadrive.presence.LocationConsent.class).orElse(null));
        out.put("lastSharedLocation", store.get(Collections.PRESENCE, uid,
                com.lucenadrive.presence.Presence.class).orElse(null));
        out.put("privacyRequests", store.query(Query.of(Collections.PRIVACY_REQUESTS).eq("userId", uid).limit(100),
                PrivacyRequest.class));

        Instant now = Instant.now(clock);
        PrivacyRequest record = new PrivacyRequest(Ids.newId(), uid, PrivacyRequest.Type.DATA_EXPORT,
                PrivacyRequest.Status.COMPLETED, "Exportação automática pelo titular", "Arquivo gerado", uid, now, now);
        store.set(Collections.PRIVACY_REQUESTS, record.id(), record);
        audit.record(auth, "DATA_EXPORTED", "user", uid, Map.of(), Map.of("privacyRequestId", record.id()),
                "Exportação de dados solicitada pelo titular");
        return out;
    }

    private static List<Ride> concat(List<Ride> a, List<Ride> b) {
        List<Ride> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }

    // ------------------------------------------------------------------ anonimização

    public record AnonymizationResult(String userId, int ridesUpdated, int messagesRedacted, int filesDeleted,
                                      int ratingsRedacted) {
    }

    /**
     * Anonimiza a conta de uma solicitação ACCOUNT_DELETION. Exige MFA recente e justificativa.
     * Bloqueia se houver corrida ativa, saldo a repassar ou repasse em andamento. Idempotente:
     * pode ser repetida se falhar no meio (ex.: indisponibilidade do armazenamento).
     */
    public AnonymizationResult anonymize(AuthUser staff, String requestId, String reason) {
        guards.requireRecentMfa(staff);
        if (reason == null || reason.trim().length() < 10) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Justificativa obrigatória (mín. 10 caracteres).");
        }
        PrivacyRequest req = store.get(Collections.PRIVACY_REQUESTS, requestId, PrivacyRequest.class)
                .orElseThrow(() -> ApiException.notFound("Solicitação"));
        if (req.type() != PrivacyRequest.Type.ACCOUNT_DELETION) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A solicitação não é de exclusão de conta.");
        }
        if (req.status() == PrivacyRequest.Status.COMPLETED || req.status() == PrivacyRequest.Status.REJECTED) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "Solicitação já encerrada.");
        }
        String uid = req.userId();
        if (uid.equals(staff.uid())) {
            throw ApiException.forbidden("Não é possível anonimizar a própria conta por este fluxo.");
        }
        User user = store.get(Collections.USERS, uid, User.class).orElseThrow(() -> ApiException.notFound("Usuário"));
        Set<Role> staffRoles = EnumSet.of(Role.SUPPORT, Role.FINANCE, Role.ADMIN, Role.OWNER);
        if (user.roles().stream().anyMatch(staffRoles::contains)) {
            throw new ApiException(ErrorCode.CONFLICT, "Remova as permissões administrativas antes de anonimizar a conta.");
        }
        assertNothingPending(uid);

        Instant now = Instant.now(clock);
        // 1) Bloqueia o acesso imediatamente.
        authAdmin.disableAccount(uid, true);
        authAdmin.revokeSessions(uid);

        // 2) Identificação e dados sensíveis.
        store.get(Collections.USER_PRIVATE, uid, UserPrivate.class).ifPresent(p -> {
            if (p.cpfHash() != null) {
                store.delete(Collections.CPF_INDEX, p.cpfHash());
            }
            store.delete(Collections.USER_PRIVATE, uid);
        });
        store.delete(Collections.DRIVER_PRIVATE, uid);
        store.delete(Collections.PIX_KEYS, uid);
        store.delete(Collections.DRIVER_LOCATIONS, uid);
        store.delete(Collections.PRESENCE, uid);
        store.set(Collections.USERS, uid, new User(uid, null, REMOVED, null, null, user.roles(), UserStatus.DELETED,
                null, new NotificationPreferences(false, false, false, false, false, true), user.termsVersion(),
                user.privacyVersion(), user.consentAt(), "Conta anonimizada a pedido do titular", user.createdAt(), now));

        store.get(Collections.PASSENGER_PROFILES, uid, PassengerProfile.class).ifPresent(p ->
                store.update(Collections.PASSENGER_PROFILES, uid, Map.of("favorites", List.of(),
                        "recentDestinations", List.of(), "updatedAt", now)));
        store.get(Collections.DRIVER_PROFILES, uid, DriverProfile.class).ifPresent(d ->
                store.update(Collections.DRIVER_PROFILES, uid, Map.of("status", "SUSPENDED",
                        "statusReason", "Conta anonimizada (LGPD)", "cnhNumberMasked", "", "documents", List.of(),
                        "updatedAt", now)));
        for (Vehicle v : store.query(Query.of(Collections.VEHICLES).eq("driverId", uid).limit(50), Vehicle.class)) {
            // O índice de placa usa hash com chave; a placa é removida do veículo e o índice liberado.
            if (v.plate() != null && !"REMOVIDA".equals(v.plate())) {
                store.delete(Collections.PLATE_INDEX, encryptor.indexHash("plate:" + Plates.normalize(v.plate()).orElse(v.plate())));
            }
            store.update(Collections.VEHICLES, v.id(), Map.of("plate", "REMOVIDA", "status", "SUSPENDED",
                    "statusReason", "Conta anonimizada (LGPD)", "updatedAt", now));
        }

        // 3) Arquivos.
        int files = 0;
        for (String prefix : List.of("driverDocuments/" + uid + "/", "profilePhotos/" + uid + "/",
                "supportAttachments/" + uid + "/")) {
            files += storage.deletePrefix(prefix);
        }

        // 4) Corridas: retira nome, foto, placa e endereços; coordenadas ficam com ~1 km de precisão.
        int rides = 0;
        int messages = 0;
        for (Ride r : all(Query.of(Collections.RIDES).eq("passengerId", uid), Ride.class)) {
            Map<String, Object> f = new HashMap<>(coarsePlaces(r));
            f.put("passenger.name", REMOVED);
            f.put("passenger.photoPath", "");
            store.update(Collections.RIDES, r.id(), f);
            rides++;
            messages += redactChat(r.id(), uid);
        }
        for (Ride r : all(Query.of(Collections.RIDES).eq("driverId", uid), Ride.class)) {
            store.update(Collections.RIDES, r.id(), Map.of("driver.name", REMOVED, "driver.photoPath", "",
                    "driver.plate", "REMOVIDA"));
            rides++;
            messages += redactChat(r.id(), uid);
        }

        // Conversas arquivadas de corridas das quais o titular (motorista) desistiu.
        for (com.lucenadrive.chat.ChatService.ChatArchive a : store.query(Query.of(Collections.RIDE_CHAT_ARCHIVES)
                .eq("driverId", uid).limit(1_000), com.lucenadrive.chat.ChatService.ChatArchive.class)) {
            messages += redactArchives(a.rideId(), uid);
        }

        // 5) Comentários escritos pelo titular; notificações e tokens de push.
        int ratings = 0;
        for (Rating r : all(Query.of(Collections.RATINGS).eq("raterId", uid), Rating.class)) {
            if (r.comment() != null) {
                store.update(Collections.RATINGS, r.id(), Map.of("comment", "", "updatedAt", now));
                ratings++;
            }
        }
        all(Query.of(Collections.NOTIFICATIONS).eq("userId", uid), Notification.class)
                .forEach(n -> store.delete(Collections.NOTIFICATIONS, n.id()));
        store.query(Query.of(Collections.PUSH_TOKENS).eq("userId", uid).limit(100), PushToken.class)
                .forEach(t -> store.delete(Collections.PUSH_TOKENS, t.id()));

        // 6) Encerra a solicitação, registra auditoria e remove a conta de autenticação.
        AnonymizationResult result = new AnonymizationResult(uid, rides, messages, files, ratings);
        store.set(Collections.PRIVACY_REQUESTS, req.id(), new PrivacyRequest(req.id(), uid, req.type(),
                PrivacyRequest.Status.COMPLETED, req.details(), "Conta anonimizada. " + reason.trim(), staff.uid(),
                req.createdAt(), now));
        audit.record(staff, "ACCOUNT_ANONYMIZED", "user", uid, Map.of("status", user.status().name()),
                Map.of("status", UserStatus.DELETED.name(), "rides", rides, "messages", messages, "files", files,
                        "ratings", ratings), reason.trim());
        authAdmin.deleteAccount(uid);
        log.info("account_anonymized uid={} rides={} files={}", uid, rides, files);
        return result;
    }

    /** Leitura completa (anonimização precisa alcançar todos os registros do titular). */
    private <T> List<T> all(Query q, Class<T> type) {
        List<T> out = new ArrayList<>();
        store.scan(q, type, 500, out::add);
        return out;
    }

    private void assertNothingPending(String uid) {
        for (String field : List.of("passengerId", "driverId")) {
            if (!store.query(Query.of(Collections.RIDES).eq(field, uid)
                    .where("status", Query.Op.IN, RideStatus.ACTIVE).limit(1), Ride.class).isEmpty()) {
                throw new ApiException(ErrorCode.CONFLICT, "Há corrida em andamento para esta conta.");
            }
        }
        if (ledger.withdrawable(LedgerEntry.driverAccount(uid)) > 0) {
            throw new ApiException(ErrorCode.CONFLICT, "Há saldo a repassar ao motorista. Conclua o repasse antes.");
        }
        boolean payoutOpen = store.query(Query.of(Collections.PAYOUT_REQUESTS).eq("ownerId", uid)
                .where("status", Query.Op.IN, List.of(PayoutRequest.Status.REQUESTED, PayoutRequest.Status.IN_VALIDATION,
                        PayoutRequest.Status.PROCESSING)).limit(1), PayoutRequest.class).size() > 0;
        if (payoutOpen) {
            throw new ApiException(ErrorCode.CONFLICT, "Há repasse em andamento para esta conta.");
        }
    }

    private static Map<String, Object> coarsePlaces(Ride r) {
        Map<String, Object> f = new HashMap<>();
        if (r.origin() != null) {
            f.put("origin", Map.of("lat", round2(r.origin().lat()), "lng", round2(r.origin().lng()), "address", REMOVED));
        }
        if (r.destination() != null) {
            f.put("destination", Map.of("lat", round2(r.destination().lat()), "lng", round2(r.destination().lng()),
                    "address", REMOVED));
        }
        f.put("route.encodedPolyline", "");
        return f;
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private int redactArchives(String rideId, String uid) {
        int n = 0;
        for (com.lucenadrive.chat.ChatService.ChatArchive a : store.query(Query.of(Collections.RIDE_CHAT_ARCHIVES)
                .eq("rideId", rideId).limit(10), com.lucenadrive.chat.ChatService.ChatArchive.class)) {
            List<ChatMessage> redacted = new ArrayList<>();
            for (ChatMessage m : a.messages() == null ? List.<ChatMessage>of() : a.messages()) {
                boolean mine = uid.equals(m.senderId());
                n += mine ? 1 : 0;
                redacted.add(mine ? new ChatMessage(m.id(), m.rideId(), m.senderId(), m.senderRole(), m.type(),
                        "[mensagem removida]", m.systemEvent(), m.clientMessageId(), m.createdAt(), null, null) : m);
            }
            store.set(Collections.RIDE_CHAT_ARCHIVES, a.id(), new com.lucenadrive.chat.ChatService.ChatArchive(a.id(),
                    a.rideId(), a.passengerId(), a.driverId(), redacted, a.archivedAt()));
        }
        return n;
    }

    private int redactChat(String rideId, String uid) {
        int n = redactArchives(rideId, uid);
        if (store.get(Collections.RIDE_CHATS, rideId, RideChat.class).isEmpty()) {
            return n;
        }
        for (ChatMessage m : store.query(Query.of(Collections.messages(rideId)).eq("senderId", uid).limit(LIMIT),
                ChatMessage.class)) {
            store.update(Collections.messages(rideId), m.id(), m.type() == ChatMessage.Type.LOCATION
                    ? Map.of("text", "[mensagem removida]", "type", "TEXT", "lat", 0.0, "lng", 0.0)
                    : Map.of("text", "[mensagem removida]"));
            n++;
        }
        return n;
    }
}
