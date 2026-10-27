package com.lucenadrive.support;

import com.lucenadrive.integration.ObjectStorage;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Armazenamento fictício: os testes "enviam" arquivos registrando metadados. */
public class FakeObjectStorage implements ObjectStorage {

    private final Map<String, ObjectInfo> objects = new ConcurrentHashMap<>();

    public void put(String path, String contentType, long size) {
        objects.put(path, new ObjectInfo(path, contentType, size));
    }

    @Override
    public Optional<ObjectInfo> stat(String path) {
        return Optional.ofNullable(objects.get(path));
    }

    @Override
    public String signedReadUrl(String path, Duration ttl) {
        return "https://storage.test/" + path + "?ttl=" + ttl.toSeconds();
    }

    @Override
    public Optional<byte[]> read(String path, long maxBytes) {
        ObjectInfo info = objects.get(path);
        return info == null ? Optional.empty() : Optional.of(("conteudo-ficticio:" + path).getBytes());
    }

    @Override
    public int deletePrefix(String prefix) {
        int before = objects.size();
        objects.keySet().removeIf(k -> k.startsWith(prefix));
        return before - objects.size();
    }

    public boolean has(String path) {
        return objects.containsKey(path);
    }
}
