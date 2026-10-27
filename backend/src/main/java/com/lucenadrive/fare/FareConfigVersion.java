package com.lucenadrive.fare;

import java.time.Instant;

/** fareConfigVersions/{configId}_v{n}: histórico imutável de cada versão tarifária. */
public record FareConfigVersion(String id, String configId, int version, FareConfig snapshot, FareConfig previous,
                                String changedBy, Instant changedAt, String reason, Instant effectiveFrom) {
}
