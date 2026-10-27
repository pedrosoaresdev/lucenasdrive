package com.lucenadrive.notification;

import com.lucenadrive.common.crypto.FieldEncryptor;
import com.lucenadrive.common.error.ApiException;
import com.lucenadrive.common.web.Ids;
import com.lucenadrive.integration.PushSender;
import com.lucenadrive.persistence.Collections;
import com.lucenadrive.persistence.DocStore;
import com.lucenadrive.persistence.Query;
import com.lucenadrive.user.NotificationPreferences;
import com.lucenadrive.user.User;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Notificações no app (Firestore) + push (FCM). Respeita preferências; alertas de segurança e
 * eventos operacionais essenciais da corrida são sempre registrados no app.
 * O push usa texto genérico quando o usuário opta por ocultar conteúdo na tela bloqueada.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final DocStore store;
    private final Clock clock;
    private final ObjectProvider<PushSender> pushSender;

    public NotificationService(DocStore store, Clock clock, ObjectProvider<PushSender> pushSender) {
        this.store = store;
        this.clock = clock;
        this.pushSender = pushSender;
    }

    public void notify(String userId, NotificationType type, String title, String body, Map<String, String> data) {
        try {
            NotificationPreferences prefs = store.get(Collections.USERS, userId, User.class)
                    .map(User::notificationPreferences).orElse(NotificationPreferences.defaults());
            if (!allowed(type, prefs)) {
                return;
            }
            Notification n = new Notification(Ids.newId(), userId, type, title, body, data, false, Instant.now(clock));
            store.set(Collections.NOTIFICATIONS, n.id(), n);
            if (prefs.push()) {
                sendPush(userId, type, title, body, data, prefs.hideContentOnLockScreen());
            }
        } catch (RuntimeException e) {
            // Notificação nunca deve derrubar a operação de negócio que a originou.
            log.warn("notification_failed user={} type={} err={}", userId, type, e.toString());
        }
    }

    static boolean allowed(NotificationType type, NotificationPreferences prefs) {
        return switch (type.category()) {
            case SECURITY, ACCOUNT -> true;
            case RIDE -> true; // eventos da corrida em andamento são operacionais
            case CHAT -> prefs.chatMessages();
            case FINANCIAL -> prefs.financial();
            case PROMOTION -> prefs.promotions();
        };
    }

    private void sendPush(String userId, NotificationType type, String title, String body,
                          Map<String, String> data, boolean hideContent) {
        PushSender sender = pushSender.getIfAvailable();
        if (sender == null) {
            return;
        }
        List<PushToken> tokens = store.query(Query.of(Collections.PUSH_TOKENS).eq("userId", userId).limit(10),
                PushToken.class);
        if (tokens.isEmpty()) {
            return;
        }
        String pushTitle = hideContent ? "Lucena Drive" : title;
        String pushBody = hideContent ? "Você tem uma nova atualização." : body;
        List<String> invalid = sender.send(tokens.stream().map(PushToken::token).toList(), pushTitle, pushBody,
                Map.of("type", type.name(), "rideId", Optional.ofNullable(data.get("rideId")).orElse("")));
        tokens.stream().filter(t -> invalid.contains(t.token()))
                .forEach(t -> store.delete(Collections.PUSH_TOKENS, t.id()));
    }

    public void registerPushToken(String userId, String token, String platform) {
        String id = FieldEncryptor.sha256Hex(token);
        store.set(Collections.PUSH_TOKENS, id, new PushToken(id, userId, token, platform, Instant.now(clock)));
    }

    public void removePushToken(String userId, String tokenId) {
        store.get(Collections.PUSH_TOKENS, tokenId, PushToken.class).ifPresent(t -> {
            if (!t.userId().equals(userId)) {
                throw ApiException.forbidden("Token pertence a outro usuário.");
            }
            store.delete(Collections.PUSH_TOKENS, tokenId);
        });
    }

    public void markRead(String userId, String notificationId) {
        Notification n = store.get(Collections.NOTIFICATIONS, notificationId, Notification.class)
                .orElseThrow(() -> ApiException.notFound("Notificação"));
        if (!n.userId().equals(userId)) {
            throw ApiException.forbidden("Notificação pertence a outro usuário.");
        }
        store.set(Collections.NOTIFICATIONS, n.id(),
                new Notification(n.id(), n.userId(), n.type(), n.title(), n.body(), n.data(), true, n.createdAt()));
    }
}
