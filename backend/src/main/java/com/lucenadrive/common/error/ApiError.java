package com.lucenadrive.common.error;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Corpo padronizado de erro devolvido por todos os endpoints. */
public record ApiError(
        String code,
        String message,
        int status,
        String path,
        Instant timestamp,
        String traceId,
        List<FieldError> fieldErrors,
        Map<String, Object> details) {

    public record FieldError(String field, String message) {
    }
}
