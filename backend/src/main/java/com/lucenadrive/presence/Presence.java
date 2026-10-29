package com.lucenadrive.presence;

import java.time.Instant;

/**
 * presence/{uid}: última posição de quem consentiu, enviada enquanto o app está aberto. Sem leitura por clientes;
 * apagada ao revogar o consentimento e removida automaticamente quando fica antiga.
 */
public record Presence(String userId, String name, Kind kind, double lat, double lng, Double accuracyM,
                       Instant updatedAt) {

    public enum Kind { PASSENGER, DRIVER }
}
