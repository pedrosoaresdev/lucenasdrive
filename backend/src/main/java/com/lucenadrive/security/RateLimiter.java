package com.lucenadrive.security;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Limitador de taxa (token bucket) em memória, por chave. Adequado a uma instância; para
 * múltiplas instâncias, substituir por implementação distribuída (ex.: Redis) — ver docs/DEPLOY.md.
 */
@Component
public class RateLimiter {

    private final Clock clock;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimiter(Clock clock) {
        this.clock = clock;
    }

    /** Consome uma ficha. capacity fichas recarregadas linearmente a cada windowMillis. */
    public boolean tryAcquire(String key, int capacity, long windowMillis) {
        long now = clock.millis();
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(capacity, now));
        synchronized (bucket) {
            double refill = (now - bucket.lastRefill) * ((double) capacity / windowMillis);
            bucket.tokens = Math.min(capacity, bucket.tokens + refill);
            bucket.lastRefill = now;
            if (bucket.tokens >= 1) {
                bucket.tokens -= 1;
                return true;
            }
            return false;
        }
    }

    private static final class Bucket {
        double tokens;
        long lastRefill;

        Bucket(int capacity, long now) {
            this.tokens = capacity;
            this.lastRefill = now;
        }
    }
}
