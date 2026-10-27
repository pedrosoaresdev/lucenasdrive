package com.lucenadrive.geo;

import com.lucenadrive.audit.AuditService;
import com.lucenadrive.common.error.ApiException;
import com.lucenadrive.common.error.ErrorCode;
import com.lucenadrive.common.web.Ids;
import com.lucenadrive.persistence.Collections;
import com.lucenadrive.persistence.DocMapper;
import com.lucenadrive.persistence.DocStore;
import com.lucenadrive.persistence.Query;
import com.lucenadrive.security.AuthUser;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

/**
 * Gestão geográfica: municípios e polígonos operacionais. Endereços e ruas não são cadastrados:
 * a busca é feita na Google Maps Platform e só os polígonos necessários às regras são armazenados.
 */
@Service
public class GeoAdminService {

    public record CityInput(String name, String state, String ibgeCode, GeoPoint center, boolean active, String timezone) {
    }

    public record AreaInput(String cityId, String name, ServiceArea.AreaType type, List<GeoPoint> polygon,
                            boolean active, Long fareMultiplierBp, String notes) {
    }

    private final DocStore store;
    private final AuditService audit;
    private final Clock clock;

    public GeoAdminService(DocStore store, AuditService audit, Clock clock) {
        this.store = store;
        this.audit = audit;
        this.clock = clock;
    }

    public List<City> cities() {
        return store.query(Query.of(Collections.CITIES).orderBy("name", Query.Direction.ASC), City.class);
    }

    public List<ServiceArea> areas(String cityId) {
        return store.query(Query.of(Collections.SERVICE_AREAS).eq("cityId", cityId), ServiceArea.class);
    }

    public City createCity(AuthUser admin, CityInput in) {
        if (in.state() == null || !in.state().matches("^[A-Z]{2}$")) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "UF inválida.");
        }
        String id = slug(in.name()) + "-" + in.state().toLowerCase(Locale.ROOT);
        Instant now = Instant.now(clock);
        City c = new City(id, in.name().trim(), in.state(), in.ibgeCode(), in.center(), in.active(),
                in.timezone() == null ? "America/Sao_Paulo" : in.timezone(), now, now);
        if (!store.createIfAbsent(Collections.CITIES, id, c)) {
            throw new ApiException(ErrorCode.CONFLICT, "Cidade já cadastrada.");
        }
        audit.record(admin, "CITY_CREATED", "city", id, null, DocMapper.toMap(c), null);
        return c;
    }

    public City updateCity(AuthUser admin, String id, CityInput in, String reason) {
        return store.transaction(tx -> {
            City prev = tx.get(Collections.CITIES, id, City.class).orElseThrow(() -> ApiException.notFound("Cidade"));
            City next = new City(id, in.name().trim(), prev.state(), in.ibgeCode(), in.center(), in.active(),
                    in.timezone() == null ? prev.timezone() : in.timezone(), prev.createdAt(), Instant.now(clock));
            tx.set(Collections.CITIES, id, next);
            audit.recordInTx(tx, admin, "CITY_UPDATED", "city", id, DocMapper.toMap(prev), DocMapper.toMap(next), reason);
            return next;
        });
    }

    public ServiceArea createArea(AuthUser admin, AreaInput in) {
        validate(in);
        if (store.get(Collections.CITIES, in.cityId(), City.class).isEmpty()) {
            throw ApiException.notFound("Cidade");
        }
        Instant now = Instant.now(clock);
        ServiceArea a = new ServiceArea(Ids.newId(), in.cityId(), in.name().trim(), in.type(), in.polygon(), in.active(),
                in.fareMultiplierBp(), in.notes(), now, now);
        store.set(Collections.SERVICE_AREAS, a.id(), a);
        audit.record(admin, "SERVICE_AREA_CREATED", "serviceArea", a.id(), null,
                java.util.Map.of("name", a.name(), "type", a.type().name(), "vertices", a.polygon().size()), null);
        return a;
    }

    public ServiceArea updateArea(AuthUser admin, String id, AreaInput in, String reason) {
        validate(in);
        return store.transaction(tx -> {
            ServiceArea prev = tx.get(Collections.SERVICE_AREAS, id, ServiceArea.class)
                    .orElseThrow(() -> ApiException.notFound("Área"));
            ServiceArea next = new ServiceArea(id, prev.cityId(), in.name().trim(), in.type(), in.polygon(), in.active(),
                    in.fareMultiplierBp(), in.notes(), prev.createdAt(), Instant.now(clock));
            tx.set(Collections.SERVICE_AREAS, id, next);
            audit.recordInTx(tx, admin, "SERVICE_AREA_UPDATED", "serviceArea", id,
                    java.util.Map.of("active", prev.active(), "type", prev.type().name(), "vertices", prev.polygon().size()),
                    java.util.Map.of("active", next.active(), "type", next.type().name(), "vertices", next.polygon().size()),
                    reason);
            return next;
        });
    }

    static void validate(AreaInput in) {
        if (in.polygon() == null || in.polygon().size() < 3 || in.polygon().size() > 500) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "O polígono deve ter entre 3 e 500 vértices.");
        }
        if (in.type() == ServiceArea.AreaType.FARE_REGION
                && (in.fareMultiplierBp() == null || in.fareMultiplierBp() < 10_000 || in.fareMultiplierBp() > 30_000)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Região tarifária exige multiplicador entre 1,0× e 3,0×.");
        }
    }

    static String slug(String s) {
        return Normalizer.normalize(s.trim().toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }
}
