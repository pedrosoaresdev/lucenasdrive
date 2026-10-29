package com.lucenadrive.presence;

import java.time.Instant;
import java.util.List;

/**
 * locationConsents/{uid}: consentimento opcional para a equipe ver a localização do usuário com o app aberto
 * (painel "O olho de Deus"). Histórico de mudanças mantido como prova do consentimento (LGPD art. 8º).
 */
public record LocationConsent(String userId, boolean enabled, String version, Instant changedAt,
                              List<Change> history) {

    public static final String CURRENT_VERSION = "2026-10";

    public record Change(boolean enabled, String version, Instant at) {
    }
}
