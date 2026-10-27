package com.lucenadrive.persistence;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

/**
 * Converte objetos de domínio (records) para mapas armazenáveis e vice-versa.
 * Instantes são gravados como epoch em milissegundos (ordenáveis e consultáveis por intervalo).
 */
public final class DocMapper {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .changeDefaultPropertyInclusion(incl -> incl.withValueInclusion(JsonInclude.Include.NON_NULL))
            .addModule(new SimpleModule("lucena-instant")
                    .addSerializer(Instant.class, new InstantMillisSerializer())
                    .addDeserializer(Instant.class, new InstantMillisDeserializer()))
            .build();

    private DocMapper() {
    }

    public static Map<String, Object> toMap(Object document) {
        if (document instanceof Map<?, ?> m) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cast = (Map<String, Object>) m;
            return MAPPER.convertValue(cast, MAP);
        }
        return MAPPER.convertValue(document, MAP);
    }

    public static <T> T fromMap(Map<String, Object> data, Class<T> type) {
        return MAPPER.convertValue(data, type);
    }

    static final class InstantMillisSerializer extends ValueSerializer<Instant> {
        @Override
        public void serialize(Instant value, JsonGenerator gen, SerializationContext ctxt) {
            gen.writeNumber(value.toEpochMilli());
        }
    }

    static final class InstantMillisDeserializer extends ValueDeserializer<Instant> {
        @Override
        public Instant deserialize(JsonParser p, DeserializationContext ctxt) {
            if (p.currentToken() == JsonToken.VALUE_STRING) {
                return Instant.parse(p.getString());
            }
            return Instant.ofEpochMilli(p.getLongValue());
        }
    }
}
