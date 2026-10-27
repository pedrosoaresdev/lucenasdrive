package com.lucenadrive.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuração da aplicação (prefixo "lucena"). Segredos chegam por variáveis de ambiente. */
@ConfigurationProperties(prefix = "lucena")
public record LucenaProperties(
        Firebase firebase,
        Security security,
        Eligibility eligibility,
        Maps maps,
        Dispatch dispatch,
        Chat chat,
        Ratings ratings,
        Payments payments,
        RateLimit rateLimit) {

    public LucenaProperties {
        firebase = firebase != null ? firebase : new Firebase(true, null, null, null, false);
        security = security != null ? security : new Security(null, null, null, null, null, null, true);
        eligibility = eligibility != null ? eligibility : new Eligibility(18, 21);
        maps = maps != null ? maps : new Maps(null, null, false);
        dispatch = dispatch != null ? dispatch : new Dispatch(null, 0, null, 0, 0, null, -1);
        chat = chat != null ? chat : new Chat(0, 0, 0, null, null);
        ratings = ratings != null ? ratings : new Ratings(true, null);
        payments = payments != null ? payments : new Payments("none", null, null, null);
        rateLimit = rateLimit != null ? rateLimit : new RateLimit(0, 0);
    }

    public record Firebase(boolean enabled, String projectId, String credentialsPath, String storageBucket,
                           boolean emulators) {
    }

    public record Security(
            String dataEncryptionKey,
            String adminMfaTokenSecret,
            Duration adminSessionTtl,
            Duration stepUpWindow,
            Duration reauthWindow,
            List<String> corsAllowedOrigins,
            boolean requireVerifiedEmail) {

        public Security {
            adminSessionTtl = adminSessionTtl != null ? adminSessionTtl : Duration.ofHours(8);
            stepUpWindow = stepUpWindow != null ? stepUpWindow : Duration.ofMinutes(5);
            reauthWindow = reauthWindow != null ? reauthWindow : Duration.ofMinutes(5);
            corsAllowedOrigins = corsAllowedOrigins != null ? corsAllowedOrigins : List.of();
        }
    }

    public record Eligibility(int passengerMinAge, int driverMinAge) {
        public Eligibility {
            passengerMinAge = passengerMinAge > 0 ? passengerMinAge : 18;
            driverMinAge = driverMinAge > 0 ? driverMinAge : 21;
        }
    }

    /**
     * serverApiKey: chave de servidor (Routes API) — nunca enviada ao navegador.
     * allowStraightLineFallback: somente desenvolvimento; gera estimativa em linha reta claramente
     * marcada como aproximada quando a Routes API não está configurada.
     */
    public record Maps(String serverApiKey, String browserApiKey, boolean allowStraightLineFallback) {
    }

    /**
     * maxArrivalDistanceMeters: distância máxima entre o motorista e o embarque para marcar "Cheguei"
     * (0 desativa; padrão 500 m). Exige posição recente do motorista.
     */
    public record Dispatch(Duration offerTimeout, int batchSize, Duration maxSearchTime, double radiusKm,
                           int maxBatches, Duration quoteTtl, int maxArrivalDistanceMeters) {
        public Dispatch {
            offerTimeout = offerTimeout != null ? offerTimeout : Duration.ofSeconds(20);
            batchSize = batchSize > 0 ? batchSize : 3;
            maxSearchTime = maxSearchTime != null ? maxSearchTime : Duration.ofMinutes(3);
            radiusKm = radiusKm > 0 ? radiusKm : 8.0;
            maxBatches = maxBatches > 0 ? maxBatches : 5;
            quoteTtl = quoteTtl != null ? quoteTtl : Duration.ofMinutes(5);
            maxArrivalDistanceMeters = maxArrivalDistanceMeters >= 0 ? maxArrivalDistanceMeters : 500;
        }
    }

    /**
     * readOnlyAfterEnd: período em que os participantes ainda leem a conversa após o término.
     * retention: período total de guarda (suporte/obrigações legais) antes da exclusão das mensagens.
     */
    public record Chat(int maxLength, int maxMessagesPerWindow, int windowSeconds, Duration readOnlyAfterEnd,
                       Duration retention) {
        public Chat {
            maxLength = maxLength > 0 ? maxLength : 500;
            maxMessagesPerWindow = maxMessagesPerWindow > 0 ? maxMessagesPerWindow : 10;
            windowSeconds = windowSeconds > 0 ? windowSeconds : 10;
            readOnlyAfterEnd = readOnlyAfterEnd != null ? readOnlyAfterEnd : Duration.ofDays(7);
            retention = retention != null ? retention : Duration.ofDays(180);
        }
    }

    public record Ratings(boolean allowSkip, Duration pendingWindow) {
        public Ratings {
            pendingWindow = pendingWindow != null ? pendingWindow : Duration.ofDays(7);
        }
    }

    /** provider: "none" até a contratação de um provedor. webhookSecret: segredo HMAC do provedor. */
    public record Payments(String provider, String apiKey, String webhookSecret, String environment) {
        public Payments {
            provider = provider != null ? provider : "none";
            environment = environment != null ? environment : "sandbox";
        }

        public boolean configured() {
            return !"none".equalsIgnoreCase(provider) && apiKey != null && !apiKey.isBlank();
        }
    }

    public record RateLimit(int requestsPerMinute, int adminRequestsPerMinute) {
        public RateLimit {
            requestsPerMinute = requestsPerMinute > 0 ? requestsPerMinute : 120;
            adminRequestsPerMinute = adminRequestsPerMinute > 0 ? adminRequestsPerMinute : 300;
        }
    }
}
