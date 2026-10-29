package com.lucenadrive.chat;

import com.lucenadrive.audit.AuditService;
import com.lucenadrive.common.error.ApiException;
import com.lucenadrive.common.error.ErrorCode;
import com.lucenadrive.common.web.PageResponse;
import com.lucenadrive.config.LucenaProperties;
import com.lucenadrive.notification.NotificationService;
import com.lucenadrive.notification.NotificationType;
import com.lucenadrive.persistence.Collections;
import com.lucenadrive.persistence.DocStore;
import com.lucenadrive.persistence.Query;
import com.lucenadrive.persistence.Tx;
import com.lucenadrive.ride.Ride;
import com.lucenadrive.security.AuthUser;
import com.lucenadrive.security.RateLimiter;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Chat por corrida. Envio sempre pelo backend (autorização, sanitização, limites, carimbo de tempo
 * do servidor, deduplicação). Recebimento em tempo real pelo cliente via listener do Firestore,
 * protegido por regras que exigem que o usuário esteja em participantIds.
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final Pattern CLIENT_ID = Pattern.compile("^[A-Za-z0-9-]{8,64}$");

    private final DocStore store;
    private final LucenaProperties props;
    private final RateLimiter limiter;
    private final NotificationService notifications;
    private final AuditService audit;
    private final Clock clock;

    public ChatService(DocStore store, LucenaProperties props, RateLimiter limiter, NotificationService notifications,
                       AuditService audit, Clock clock) {
        this.store = store;
        this.props = props;
        this.limiter = limiter;
        this.notifications = notifications;
        this.audit = audit;
        this.clock = clock;
    }

    /** Cria a conversa quando o motorista aceita a corrida (dentro da transação de aceite). */
    public void openInTx(Tx tx, Ride ride, Instant now) {
        RideChat chat = new RideChat(ride.id(), ride.id(), ride.passengerId(), ride.driverId(),
                List.of(ride.passengerId(), ride.driverId()), RideChat.Status.OPEN, now, null, null, null, new HashMap<>());
        tx.set(Collections.RIDE_CHATS, ride.id(), chat);
    }

    public record ChatArchive(String id, String rideId, String passengerId, String driverId,
                              List<ChatMessage> messages, Instant archivedAt) {
    }

    /**
     * Redespacho: a conversa com o motorista que desistiu é arquivada (sem acesso de clientes; equipe
     * consulta com auditoria) e removida, para que o novo motorista não leia mensagens anteriores.
     */
    public void archiveForRedispatch(String rideId) {
        RideChat chat = store.get(Collections.RIDE_CHATS, rideId, RideChat.class).orElse(null);
        if (chat == null) {
            return;
        }
        List<ChatMessage> msgs = store.query(Query.of(Collections.messages(rideId))
                .orderBy("createdAt", Query.Direction.ASC).limit(1000), ChatMessage.class);
        Instant now = Instant.now(clock);
        String id = rideId + "_" + now.toEpochMilli();
        store.set(Collections.RIDE_CHAT_ARCHIVES, id,
                new ChatArchive(id, rideId, chat.passengerId(), chat.driverId(), msgs, now));
        msgs.forEach(m -> store.delete(Collections.messages(rideId), m.id()));
        store.delete(Collections.RIDE_CHATS, rideId);
    }

    public List<ChatArchive> archives(String rideId) {
        return store.query(Query.of(Collections.RIDE_CHAT_ARCHIVES).eq("rideId", rideId).limit(10), ChatArchive.class);
    }

    /** Passa para somente leitura quando a corrida termina. */
    public void closeForWriting(String rideId) {
        Instant now = Instant.now(clock);
        store.get(Collections.RIDE_CHATS, rideId, RideChat.class).ifPresent(chat -> {
            if (chat.status() == RideChat.Status.OPEN) {
                store.update(Collections.RIDE_CHATS, rideId, Map.of(
                        "status", RideChat.Status.READ_ONLY.name(),
                        "readOnlySince", now,
                        "closesAt", now.plus(props.chat().readOnlyAfterEnd())));
            }
        });
    }

    /** Mensagem de sistema idempotente (um evento = uma mensagem). */
    public void systemMessage(String rideId, String event, String text) {
        if (store.get(Collections.RIDE_CHATS, rideId, RideChat.class).isEmpty()) {
            return;
        }
        String id = "sys_" + event;
        ChatMessage msg = new ChatMessage(id, rideId, "system", ChatMessage.SenderRole.SYSTEM, ChatMessage.Type.SYSTEM,
                text, event, null, Instant.now(clock));
        store.createIfAbsent(Collections.messages(rideId), id, msg);
    }

    public ChatMessage send(AuthUser user, String rideId, String clientMessageId, String rawText) {
        if (clientMessageId == null || !CLIENT_ID.matcher(clientMessageId).matches()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Identificador de mensagem inválido.");
        }
        RideChat chat = requireParticipant(user, rideId);
        if (chat.status() != RideChat.Status.OPEN) {
            throw new ApiException(ErrorCode.CHAT_CLOSED, "O chat desta corrida está encerrado.");
        }
        String text = MessageSanitizer.sanitize(rawText);
        if (text.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Mensagem vazia.");
        }
        if (text.codePointCount(0, text.length()) > props.chat().maxLength()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "Mensagem excede " + props.chat().maxLength() + " caracteres.");
        }
        String id = user.uid() + "_" + clientMessageId;
        var existing = store.get(Collections.messages(rideId), id, ChatMessage.class);
        if (existing.isPresent()) {
            return existing.get(); // reenvio após falha de rede: idempotente
        }
        if (!limiter.tryAcquire("chat:" + user.uid(), props.chat().maxMessagesPerWindow(),
                props.chat().windowSeconds() * 1000L)) {
            throw new ApiException(ErrorCode.RATE_LIMITED, "Você está enviando mensagens rápido demais.");
        }
        ChatMessage.SenderRole role = user.uid().equals(chat.driverId())
                ? ChatMessage.SenderRole.DRIVER : ChatMessage.SenderRole.PASSENGER;
        Instant now = Instant.now(clock);
        ChatMessage msg = new ChatMessage(id, rideId, user.uid(), role, ChatMessage.Type.TEXT, text, null,
                clientMessageId, now);
        if (!store.createIfAbsent(Collections.messages(rideId), id, msg)) {
            return store.get(Collections.messages(rideId), id, ChatMessage.class).orElse(msg);
        }
        store.update(Collections.RIDE_CHATS, rideId, Map.of("lastMessageAt", now));
        String recipient = role == ChatMessage.SenderRole.DRIVER ? chat.passengerId() : chat.driverId();
        notifications.notify(recipient, NotificationType.CHAT_MESSAGE,
                role == ChatMessage.SenderRole.DRIVER ? "Mensagem do motorista" : "Mensagem do passageiro",
                text.length() > 80 ? text.substring(0, 80) + "…" : text, Map.of("rideId", rideId));
        return msg;
    }

    /** Histórico paginado (mais recentes primeiro; cursor = createdAt em ms da última mensagem). */
    public PageResponse<ChatMessage> history(AuthUser user, String rideId, Long before, int limit) {
        RideChat chat = requireParticipant(user, rideId);
        if (chat.status() == RideChat.Status.CLOSED) {
            throw new ApiException(ErrorCode.CHAT_CLOSED, "O histórico deste chat não está mais disponível.");
        }
        return page(rideId, before, limit);
    }

    public void markRead(AuthUser user, String rideId) {
        requireParticipant(user, rideId);
        store.update(Collections.RIDE_CHATS, rideId, Map.of("lastReadAt." + user.uid(), clock.millis()));
    }

    /** Acesso administrativo: restrito, com justificativa obrigatória e auditado. */
    public PageResponse<ChatMessage> adminHistory(AuthUser staff, String rideId, String reason, Long before, int limit) {
        if (reason == null || reason.isBlank() || reason.length() < 10) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Informe a justificativa do acesso (mín. 10 caracteres).");
        }
        store.get(Collections.RIDE_CHATS, rideId, RideChat.class).orElseThrow(() -> ApiException.notFound("Chat"));
        audit.record(staff, "ADMIN_CHAT_ACCESS", "rideChat", rideId, null, null, reason);
        return page(rideId, before, limit);
    }

    /** Conversas arquivadas em redespachos (motoristas que desistiram), com justificativa auditada. */
    public List<ChatArchive> adminArchives(AuthUser staff, String rideId, String reason) {
        if (reason == null || reason.length() < 10) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Informe a justificativa do acesso (mín. 10 caracteres).");
        }
        audit.record(staff, "ADMIN_CHAT_ARCHIVE_ACCESS", "rideChat", rideId, null, null, reason);
        return archives(rideId);
    }

    private PageResponse<ChatMessage> page(String rideId, Long before, int limit) {
        int size = Math.min(Math.max(limit, 1), 100);
        Query q = Query.of(Collections.messages(rideId)).orderBy("createdAt", Query.Direction.DESC).limit(size);
        if (before != null) {
            q.startAfter(before);
        }
        List<ChatMessage> items = store.query(q, ChatMessage.class);
        return PageResponse.of(items, size, m -> m.createdAt().toEpochMilli());
    }

    private RideChat requireParticipant(AuthUser user, String rideId) {
        RideChat chat = store.get(Collections.RIDE_CHATS, rideId, RideChat.class)
                .orElseThrow(() -> ApiException.notFound("Chat"));
        // A autorização usa os participantes gravados pelo servidor, não o que o cliente envia.
        if (!chat.isParticipant(user.uid())) {
            throw ApiException.forbidden("Você não participa desta corrida.");
        }
        return chat;
    }

    /** Encerra a leitura após o período pós-corrida e apaga mensagens após a retenção. */
    @Scheduled(fixedDelayString = "${lucena.chat.maintenance-ms:600000}")
    public void maintenance() {
        if (!schedulerLock.tryAcquire("chat-maintenance", java.time.Duration.ofMinutes(10))) {
            return;
        }
        try {
            Instant now = Instant.now(clock);
            for (RideChat c : store.query(Query.of(Collections.RIDE_CHATS).eq("status", RideChat.Status.READ_ONLY)
                    .where("closesAt", Query.Op.LTE, now).limit(200), RideChat.class)) {
                store.update(Collections.RIDE_CHATS, c.id(), Map.of("status", RideChat.Status.CLOSED.name()));
            }
            Instant purgeBefore = now.minus(props.chat().retention());
            for (RideChat c : store.query(Query.of(Collections.RIDE_CHATS).eq("status", RideChat.Status.CLOSED)
                    .where("readOnlySince", Query.Op.LT, purgeBefore).limit(50), RideChat.class)) {
                for (ChatMessage m : store.query(Query.of(Collections.messages(c.id())).limit(500), ChatMessage.class)) {
                    store.delete(Collections.messages(c.id()), m.id());
                }
                store.delete(Collections.RIDE_CHATS, c.id());
            }
            for (ChatArchive a : store.query(Query.of(Collections.RIDE_CHAT_ARCHIVES)
                    .where("archivedAt", Query.Op.LT, purgeBefore).limit(100), ChatArchive.class)) {
                store.delete(Collections.RIDE_CHAT_ARCHIVES, a.id());
            }
        } catch (RuntimeException e) {
            log.warn("chat_maintenance_failed err={}", e.toString());
        }
    }

    private com.lucenadrive.scheduling.SchedulerLock schedulerLock;

    @org.springframework.beans.factory.annotation.Autowired
    void setSchedulerLock(com.lucenadrive.scheduling.SchedulerLock schedulerLock) {
        this.schedulerLock = schedulerLock;
    }
}
