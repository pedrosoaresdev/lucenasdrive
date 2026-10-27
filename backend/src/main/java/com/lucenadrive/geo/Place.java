package com.lucenadrive.geo;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;

/** Local escolhido pelo usuário (coordenadas + endereço formatado retornado pelo Google). */
public record Place(
        @DecimalMin("-90.0") @DecimalMax("90.0") double lat,
        @DecimalMin("-180.0") @DecimalMax("180.0") double lng,
        @Size(max = 300) String address,
        @Size(max = 300) String placeId) {

    public GeoPoint point() {
        return new GeoPoint(lat, lng);
    }
}
