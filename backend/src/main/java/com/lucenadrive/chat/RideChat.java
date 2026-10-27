package com.lucenadrive.chat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * rideChats/{rideId}: conversa da corrida. participantIds é usado pelas regras do Firestore para
 * liberar leitura das mensagens somente aos dois participantes.
 * OPEN: aceita mensagens; READ_ONLY: corrida encerrada, apenas leitura; CLOSED: sem acesso do cliente.
 */
public record RideChat(String id, String rideId, String passengerId, String driverId, List<String> participantIds,
                       Status status, Instant createdAt, Instant readOnlySince, Instant closesAt,
                       Instant lastMessageAt, Map<String, Long> lastReadAt) {

    public enum Status { OPEN, READ_ONLY, CLOSED }

    public boolean isParticipant(String uid) {
        return participantIds != null && participantIds.contains(uid);
    }
}
