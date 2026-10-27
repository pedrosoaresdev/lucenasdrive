package com.lucenadrive.integration;

import java.time.Duration;
import java.util.Optional;

/** Acesso privado a arquivos (documentos de motoristas, anexos de suporte). */
public interface ObjectStorage {

    record ObjectInfo(String path, String contentType, long sizeBytes) {
    }

    Optional<ObjectInfo> stat(String path);

    /** URL assinada de curta duração para visualização autorizada. */
    String signedReadUrl(String path, Duration ttl);

    /** Conteúdo do arquivo (para entrega autorizada pelo próprio backend), limitado a maxBytes. */
    Optional<byte[]> read(String path, long maxBytes);

    /** Remove todos os arquivos sob o prefixo (ex.: anonimização LGPD). Retorna a quantidade removida. */
    int deletePrefix(String prefix);
}
