package com.lucenadrive.integration.firebase;

import com.google.cloud.storage.Blob;
import com.google.cloud.storage.Storage;
import com.lucenadrive.common.error.ApiException;
import com.lucenadrive.common.error.ErrorCode;
import com.lucenadrive.integration.ObjectStorage;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public class FirebaseObjectStorage implements ObjectStorage {

    private final Storage storage;
    private final String bucket;

    public FirebaseObjectStorage(Storage storage, String bucket) {
        this.storage = storage;
        this.bucket = bucket;
    }

    @Override
    public Optional<ObjectInfo> stat(String path) {
        try {
            Blob blob = storage.get(bucket, path);
            if (blob == null || !blob.exists()) {
                return Optional.empty();
            }
            return Optional.of(new ObjectInfo(path, blob.getContentType(), blob.getSize() == null ? 0 : blob.getSize()));
        } catch (RuntimeException e) {
            throw new ApiException(ErrorCode.INTEGRATION_FAILURE, "Falha ao consultar o armazenamento.");
        }
    }

    @Override
    public String signedReadUrl(String path, Duration ttl) {
        try {
            Blob blob = storage.get(bucket, path);
            if (blob == null) {
                throw ApiException.notFound("Arquivo");
            }
            return blob.signUrl(ttl.toSeconds(), TimeUnit.SECONDS, Storage.SignUrlOption.withV4Signature()).toString();
        } catch (ApiException e) {
            throw e;
        } catch (RuntimeException e) {
            // URLs assinadas exigem credencial de conta de serviço com chave (indisponível no emulador).
            throw new ApiException(ErrorCode.INTEGRATION_NOT_CONFIGURED,
                    "Visualização de documentos requer credencial de conta de serviço com permissão de assinatura.");
        }
    }

    @Override
    public Optional<byte[]> read(String path, long maxBytes) {
        try {
            Blob blob = storage.get(bucket, path);
            if (blob == null || !blob.exists()) {
                return Optional.empty();
            }
            if (blob.getSize() != null && blob.getSize() > maxBytes) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "Arquivo maior que o permitido.");
            }
            return Optional.of(blob.getContent());
        } catch (ApiException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ApiException(ErrorCode.INTEGRATION_FAILURE, "Falha ao ler o arquivo.");
        }
    }

    @Override
    public int deletePrefix(String prefix) {
        if (prefix == null || prefix.isBlank() || !prefix.endsWith("/")) {
            throw new IllegalArgumentException("Prefixo inválido: " + prefix);
        }
        try {
            int count = 0;
            for (Blob blob : storage.list(bucket, Storage.BlobListOption.prefix(prefix)).iterateAll()) {
                if (blob.delete()) {
                    count++;
                }
            }
            return count;
        } catch (RuntimeException e) {
            throw new ApiException(ErrorCode.INTEGRATION_FAILURE, "Falha ao remover arquivos.");
        }
    }
}
