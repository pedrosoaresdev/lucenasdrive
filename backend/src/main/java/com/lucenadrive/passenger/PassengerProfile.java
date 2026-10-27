package com.lucenadrive.passenger;

import com.lucenadrive.geo.Place;
import java.time.Instant;
import java.util.List;

/**
 * passengerProfiles/{uid}: favoritos, destinos recentes, contadores e agregado das avaliações recebidas
 * dos motoristas (avaliação recíproca). A média é calculada só no backend.
 */
public record PassengerProfile(String id, List<FavoritePlace> favorites, List<Place> recentDestinations,
                               long completedRides, long cancelledRides, Instant createdAt, Instant updatedAt,
                               long ratingSum, long ratingCount) {

    /** Média exibida aos motoristas nas ofertas; nula até haver avaliações. */
    public Double ratingAvgOrNull() {
        return ratingCount <= 0 ? null : Math.round((double) ratingSum / ratingCount * 100) / 100.0;
    }

    public PassengerProfile withRating(long sum, long count, Instant now) {
        return new PassengerProfile(id, favorites, recentDestinations, completedRides, cancelledRides, createdAt, now,
                sum, count);
    }

    public record FavoritePlace(Kind kind, String label, Place place) {
        public enum Kind { HOME, WORK, OTHER }
    }

    public PassengerProfile afterCompletedRide(Place destination, Instant now) {
        java.util.List<Place> recents = new java.util.ArrayList<>();
        recents.add(destination);
        if (recentDestinations != null) {
            recentDestinations.stream()
                    .filter(p -> Math.abs(p.lat() - destination.lat()) > 1e-4 || Math.abs(p.lng() - destination.lng()) > 1e-4)
                    .limit(9)
                    .forEach(recents::add);
        }
        return new PassengerProfile(id, favorites, recents, completedRides + 1, cancelledRides, createdAt, now,
                ratingSum, ratingCount);
    }

    public PassengerProfile afterCancelledRide(Instant now) {
        return new PassengerProfile(id, favorites, recentDestinations, completedRides, cancelledRides + 1, createdAt, now,
                ratingSum, ratingCount);
    }

    public PassengerProfile withFavorites(List<FavoritePlace> favs, Instant now) {
        return new PassengerProfile(id, favs, recentDestinations, completedRides, cancelledRides, createdAt, now,
                ratingSum, ratingCount);
    }
}
