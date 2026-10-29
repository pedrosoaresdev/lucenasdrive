package com.lucenadrive.demand;

import java.time.Instant;

/** demandStates/{cityId}: último cálculo de demanda × oferta (atualizado a cada minuto pelo agendador). */
public record DemandState(String cityId, long demand, long supply, double ratio, long multiplierBp, Instant updatedAt) {
}
