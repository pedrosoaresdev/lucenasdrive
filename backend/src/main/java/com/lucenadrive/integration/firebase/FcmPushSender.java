package com.lucenadrive.integration.firebase;

import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.MulticastMessage;
import com.google.firebase.messaging.Notification;
import com.lucenadrive.integration.PushSender;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FcmPushSender implements PushSender {

    private static final Logger log = LoggerFactory.getLogger(FcmPushSender.class);

    private final FirebaseMessaging messaging;

    public FcmPushSender(FirebaseMessaging messaging) {
        this.messaging = messaging;
    }

    @Override
    public List<String> send(List<String> tokens, String title, String body, Map<String, String> data) {
        if (tokens.isEmpty()) {
            return List.of();
        }
        MulticastMessage msg = MulticastMessage.builder()
                .addAllTokens(tokens)
                .setNotification(Notification.builder().setTitle(title).setBody(body).build())
                .putAllData(data)
                .build();
        List<String> invalid = new ArrayList<>();
        try {
            BatchResponse res = messaging.sendEachForMulticast(msg);
            for (int i = 0; i < res.getResponses().size(); i++) {
                var r = res.getResponses().get(i);
                if (!r.isSuccessful() && r.getException() != null
                        && r.getException().getMessagingErrorCode() == MessagingErrorCode.UNREGISTERED) {
                    invalid.add(tokens.get(i));
                }
            }
        } catch (FirebaseMessagingException e) {
            log.warn("fcm_send_failed code={}", e.getMessagingErrorCode());
        }
        return invalid;
    }
}
