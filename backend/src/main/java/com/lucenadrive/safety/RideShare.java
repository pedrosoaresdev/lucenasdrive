package com.lucenadrive.safety;

import java.time.Instant;

/**
 * rideShares/{sha256(token)}: link de acompanhamento enviado pelo passageiro a um contato de confiança.
 * O token só existe no link (aqui fica o hash). Sem leitura direta por clientes.
 */
public record RideShare(String id, String rideId, String passengerId, boolean revoked, Instant createdAt,
                        Instant expiresAt) {
}
