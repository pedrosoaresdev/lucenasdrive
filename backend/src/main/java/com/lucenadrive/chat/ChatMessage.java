package com.lucenadrive.chat;

import java.time.Instant;

/**
 * rideChats/{rideId}/messages/{id}. id = {senderId}_{clientMessageId} para mensagens de usuário
 * (reenvios não duplicam) e sys_{evento} para mensagens de sistema geradas pelo backend.
 * type permite extensão futura (IMAGE, LOCATION) sem migração.
 */
public record ChatMessage(String id, String rideId, String senderId, SenderRole senderRole, Type type, String text,
                          String systemEvent, String clientMessageId, Instant createdAt) {

    public enum SenderRole { PASSENGER, DRIVER, SYSTEM }

    public enum Type { TEXT, SYSTEM }
}
