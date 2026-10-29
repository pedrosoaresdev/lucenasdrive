package com.lucenadrive.integration;

import com.lucenadrive.common.error.ApiException;
import com.lucenadrive.common.error.ErrorCode;
import java.time.Duration;
import java.util.Optional;

/**
 * Usado quando o bucket do Storage não existe (ex.: projeto Firebase sem o plano exigido). O sistema
 * sobe normalmente; envios e leituras de arquivos respondem "integração pendente" em vez de falhar no início.
 */
public class UnavailableObjectStorage implements ObjectStorage {

    private static ApiException unavailable() {
        return new ApiException(ErrorCode.INTEGRATION_NOT_CONFIGURED,
                "Armazenamento de arquivos indisponível: o Storage do Firebase ainda não foi configurado.");
    }

    @Override
    public Optional<ObjectInfo> stat(String path) {
        throw unavailable();
    }

    @Override
    public String signedReadUrl(String path, Duration ttl) {
        throw unavailable();
    }

    @Override
    public Optional<byte[]> read(String path, long maxBytes) {
        throw unavailable();
    }

    @Override
    public int deletePrefix(String prefix) {
        return 0; // sem bucket não há arquivos a remover
    }
}
