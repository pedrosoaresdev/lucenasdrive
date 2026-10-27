package com.lucenadrive.ride;

import com.lucenadrive.fare.FareBreakdown;
import com.lucenadrive.fare.VehicleCategory;
import com.lucenadrive.geo.Place;
import java.time.Instant;

/**
 * fareQuotes/{id}: cotação calculada pelo servidor e válida por poucos minutos. A corrida é
 * criada a partir da cotação — o cliente nunca envia preço.
 */
public record FareQuote(String id, String passengerId, String cityId, VehicleCategory category, Place origin,
                        Place destination, Ride.RouteInfo route, FareBreakdown fare, long multiplierBp,
                        String couponCode, String couponMessage, Instant expiresAt, Instant createdAt) {
}
