package com.lucenadrive.security;

import com.lucenadrive.common.error.ApiError;
import com.lucenadrive.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Escreve {@link ApiError} diretamente na resposta (usado por filtros e handlers de segurança). */
@Component
public class JsonErrorWriter {

    private final JsonMapper mapper;
    private final Clock clock;

    public JsonErrorWriter(JsonMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    public void write(HttpServletRequest req, HttpServletResponse res, ErrorCode code, String message) throws IOException {
        ApiError body = new ApiError(code.name(), message, code.status().value(), req.getRequestURI(),
                Instant.now(clock), MDC.get("traceId"), List.of(), Map.of());
        res.setStatus(code.status().value());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        mapper.writeValue(res.getOutputStream(), body);
    }
}
