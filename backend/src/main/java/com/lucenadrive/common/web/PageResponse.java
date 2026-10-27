package com.lucenadrive.common.web;

import java.util.List;

/** Página com cursor opaco (valor do campo de ordenação do último item). */
public record PageResponse<T>(List<T> items, String nextCursor) {

    public static <T> PageResponse<T> of(List<T> items, int limit, java.util.function.Function<T, Object> cursorOf) {
        String next = items.size() == limit && !items.isEmpty()
                ? String.valueOf(cursorOf.apply(items.get(items.size() - 1)))
                : null;
        return new PageResponse<>(items, next);
    }
}
