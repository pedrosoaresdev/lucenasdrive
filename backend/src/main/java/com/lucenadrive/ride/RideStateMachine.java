package com.lucenadrive.ride;

import static com.lucenadrive.ride.RideStatus.ACCEPTED;
import static com.lucenadrive.ride.RideStatus.CANCELLED;
import static com.lucenadrive.ride.RideStatus.COMPLETED;
import static com.lucenadrive.ride.RideStatus.DRIVER_ARRIVED;
import static com.lucenadrive.ride.RideStatus.DRIVER_ARRIVING;
import static com.lucenadrive.ride.RideStatus.IN_PROGRESS;
import static com.lucenadrive.ride.RideStatus.REQUESTED;
import static com.lucenadrive.ride.RideStatus.SEARCHING_DRIVER;

import com.lucenadrive.common.error.ApiException;
import com.lucenadrive.common.error.ErrorCode;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Máquina de estados da corrida, validada no servidor. Cada transição define quais atores podem
 * executá-la; qualquer outra combinação é rejeitada com INVALID_STATE_TRANSITION ou FORBIDDEN.
 */
public final class RideStateMachine {

    private record Edge(RideStatus to, Set<ActorRole> actors) {
    }

    private static final Map<RideStatus, Set<Edge>> EDGES = new EnumMap<>(RideStatus.class);

    static {
        allow(REQUESTED, SEARCHING_DRIVER, ActorRole.SYSTEM);
        allow(REQUESTED, CANCELLED, ActorRole.PASSENGER, ActorRole.SYSTEM, ActorRole.STAFF);
        allow(SEARCHING_DRIVER, ACCEPTED, ActorRole.DRIVER);
        allow(SEARCHING_DRIVER, CANCELLED, ActorRole.PASSENGER, ActorRole.SYSTEM, ActorRole.STAFF);
        allow(ACCEPTED, DRIVER_ARRIVING, ActorRole.DRIVER);
        allow(ACCEPTED, DRIVER_ARRIVED, ActorRole.DRIVER);
        allow(ACCEPTED, CANCELLED, ActorRole.PASSENGER, ActorRole.DRIVER, ActorRole.STAFF);
        allow(DRIVER_ARRIVING, DRIVER_ARRIVED, ActorRole.DRIVER);
        allow(DRIVER_ARRIVING, CANCELLED, ActorRole.PASSENGER, ActorRole.DRIVER, ActorRole.STAFF);
        allow(DRIVER_ARRIVED, IN_PROGRESS, ActorRole.DRIVER);
        allow(DRIVER_ARRIVED, CANCELLED, ActorRole.PASSENGER, ActorRole.DRIVER, ActorRole.STAFF);
        allow(IN_PROGRESS, COMPLETED, ActorRole.DRIVER);
        // Viagem em andamento só pode ser cancelada pela equipe (ocorrência registrada).
        allow(IN_PROGRESS, CANCELLED, ActorRole.STAFF);
    }

    private RideStateMachine() {
    }

    private static void allow(RideStatus from, RideStatus to, ActorRole... actors) {
        EDGES.computeIfAbsent(from, k -> new java.util.HashSet<>())
                .add(new Edge(to, EnumSet.copyOf(java.util.List.of(actors))));
    }

    public static boolean canTransition(RideStatus from, RideStatus to, ActorRole actor) {
        return EDGES.getOrDefault(from, Set.of()).stream()
                .anyMatch(e -> e.to() == to && e.actors().contains(actor));
    }

    public static void assertTransition(RideStatus from, RideStatus to, ActorRole actor) {
        Set<Edge> edges = EDGES.getOrDefault(from, Set.of());
        boolean edgeExists = edges.stream().anyMatch(e -> e.to() == to);
        if (!edgeExists) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Transição inválida: " + from + " → " + to + ".",
                    Map.of("from", from.name(), "to", to.name()));
        }
        if (!canTransition(from, to, actor)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Você não pode executar esta ação nesta etapa da corrida.");
        }
    }
}
