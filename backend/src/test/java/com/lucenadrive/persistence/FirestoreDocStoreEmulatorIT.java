package com.lucenadrive.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FirestoreOptions;
import com.lucenadrive.ride.Ride;
import com.lucenadrive.ride.RideStatus;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Consultas reais contra o Firestore Emulator (mesmo cliente do Firestore de produção). Roda só quando
 * FIRESTORE_EMULATOR_HOST está definido — pega diferenças que o DocStore em memória não mostra.
 */
@EnabledIfEnvironmentVariable(named = "FIRESTORE_EMULATOR_HOST", matches = ".+")
class FirestoreDocStoreEmulatorIT {

    private static Firestore firestore() {
        return FirestoreOptions.newBuilder().setProjectId("demo-lucena-drive")
                .setCredentials(GoogleCredentials.create(new AccessToken("owner", new Date(Long.MAX_VALUE))))
                .build().getService();
    }

    @Test
    void liveRidesQueryUsedByOperationalMap() {
        DocStore store = new FirestoreDocStore(firestore());
        Query q = Query.of(Collections.RIDES).where("status", Query.Op.IN, RideStatus.ACTIVE);
        List<Ride> rides = store.query(q.limit(500), Ride.class);
        assertThat(rides).isNotNull();
    }

    @Test
    void presenceRangeQueryAndScheduledFareQuery() {
        DocStore store = new FirestoreDocStore(firestore());
        store.query(Query.of(Collections.PRESENCE).where("updatedAt", Query.Op.GTE, java.time.Instant.now()).limit(10), Map.class);
        store.query(Query.of(Collections.FARE_CONFIGS).where("scheduled.effectiveFrom", Query.Op.LTE, java.time.Instant.now()).limit(10), Map.class);
    }

    @Test
    void scanReadsEveryDocumentAcrossPagesWithoutDuplicates() {
        DocStore store = new FirestoreDocStore(firestore());
        String col = "scanTest" + System.nanoTime();
        java.time.Instant t0 = java.time.Instant.parse("2026-01-01T00:00:00Z");
        for (int i = 0; i < 25; i++) {
            // horários repetidos de propósito: o cursor precisa desempatar pelo ID do documento
            store.set(col, "d" + i, Map.of("account", i % 5 == 0 ? "other" : "acc", "createdAt", t0.plusSeconds(i / 3), "n", i));
        }
        java.util.List<Map> eq = new java.util.ArrayList<>();
        store.scan(Query.of(col).eq("account", "acc"), Map.class, 4, eq::add);
        assertThat(eq).hasSize(20);
        assertThat(eq.stream().map(m -> m.get("n")).distinct().count()).isEqualTo(20);
        java.util.List<Map> range = new java.util.ArrayList<>();
        store.scan(Query.of(col).where("createdAt", Query.Op.GTE, t0.plusSeconds(2)).limit(3), Map.class, 4, range::add);
        assertThat(range).hasSize(19); // limite da consulta é ignorado no scan
        assertThat(range.stream().map(m -> m.get("n")).distinct().count()).isEqualTo(19);
    }
}
