package com.lucenadrive.geo;

import java.time.Instant;

/** cities/{id}: município atendido. */
public record City(String id, String name, String state, String ibgeCode, GeoPoint center, boolean active,
                   String timezone, Instant createdAt, Instant updatedAt) {
}
