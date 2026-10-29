package com.lucenadrive.chat;

import java.time.Instant;

/**
 * rideChats/{rideId}/messages/{id}. id = {senderId}_{clientMessageId} para mensagens de usuário
 * (reenvios não duplicam) e sys_{evento} para mensagens de sistema geradas pelo backend.
 * type: TEXT, SYSTEM ou LOCATION (posição compartilhada pelo participante: lat/lng).
 */
public record ChatMessage(String id, String rideId, String senderId, SenderRole senderRole, Type type, String text,
                          String systemEvent, String clientMessageId, Instant createdAt, Double lat, Double lng) {

    public enum SenderRole { PASSENGER, DRIVER, SYSTEM }

    public enum Type { TEXT, SYSTEM, LOCATION }
}
