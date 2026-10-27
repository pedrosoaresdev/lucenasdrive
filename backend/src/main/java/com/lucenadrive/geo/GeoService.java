package com.lucenadrive.geo;

import com.lucenadrive.common.error.ApiException;
import com.lucenadrive.common.error.ErrorCode;
import com.lucenadrive.common.money.Money;
import com.lucenadrive.persistence.Collections;
import com.lucenadrive.persistence.DocStore;
import com.lucenadrive.persistence.Query;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** Regras geográficas validadas no servidor: cobertura, restrições e regiões tarifárias. */
@Service
public class GeoService {

    private final DocStore store;

    public GeoService(DocStore store) {
        this.store = store;
    }

    public List<City> activeCities() {
        return store.query(Query.of(Collections.CITIES).eq("active", true), City.class);
    }

    public List<ServiceArea> activeAreas(String cityId) {
        return store.query(Query.of(Collections.SERVICE_AREAS).eq("cityId", cityId).eq("active", true), ServiceArea.class);
    }

    /** Retorna a cidade cuja área de cobertura ativa contém o ponto. */
    public Optional<City> cityAt(GeoPoint p) {
        for (City city : activeCities()) {
            boolean covered = activeAreas(city.id()).stream()
                    .anyMatch(a -> a.type() == ServiceArea.AreaType.COVERAGE && GeoMath.contains(a.polygon(), p));
            if (covered) {
                return Optional.of(city);
            }
        }
        return Optional.empty();
    }

    /**
     * Valida origem e destino: ambos em cobertura da mesma cidade e fora de zonas restritas.
     * @return cidade atendida
     */
    public City validateTrip(GeoPoint origin, GeoPoint destination) {
        City city = cityAt(origin).orElseThrow(() -> new ApiException(ErrorCode.OUTSIDE_SERVICE_AREA,
                "O local de embarque está fora da área atendida pelo Lucena Drive."));
        List<ServiceArea> areas = activeAreas(city.id());
        boolean destCovered = areas.stream()
                .anyMatch(a -> a.type() == ServiceArea.AreaType.COVERAGE && GeoMath.contains(a.polygon(), destination));
        if (!destCovered) {
            throw new ApiException(ErrorCode.OUTSIDE_SERVICE_AREA, "O destino está fora da área atendida.");
        }
        for (ServiceArea a : areas) {
            if (a.type() == ServiceArea.AreaType.RESTRICTED
                    && (GeoMath.contains(a.polygon(), origin) || GeoMath.contains(a.polygon(), destination))) {
                throw new ApiException(ErrorCode.OUTSIDE_SERVICE_AREA,
                        "Embarque ou desembarque não permitido na área \"" + a.name() + "\".");
            }
        }
        return city;
    }

    /** Multiplicador da região tarifária que contém a origem (maior, se houver sobreposição). */
    public long regionMultiplierBp(String cityId, GeoPoint origin) {
        return activeAreas(cityId).stream()
                .filter(a -> a.type() == ServiceArea.AreaType.FARE_REGION && a.fareMultiplierBp() != null)
                .filter(a -> GeoMath.contains(a.polygon(), origin))
                .mapToLong(ServiceArea::fareMultiplierBp)
                .max()
                .orElse(Money.BP_ONE);
    }
}
