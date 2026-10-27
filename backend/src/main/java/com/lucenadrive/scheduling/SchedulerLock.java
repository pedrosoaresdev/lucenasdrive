package com.lucenadrive.scheduling;

import com.lucenadrive.persistence.Collections;
import com.lucenadrive.persistence.DocStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Trava distribuída para tarefas agendadas (lease em schedulerLocks/{tarefa}). Com várias instâncias do
 * backend, só a que detém a trava executa a rodada; se ela cair, outra assume após o vencimento.
 * As operações de negócio continuam protegidas por transações — a trava evita trabalho redundante.
 */
@Component
public class SchedulerLock {

    private static final Logger log = LoggerFactory.getLogger(SchedulerLock.class);

    public record Lease(String id, String owner, Instant until) {
    }

    private final DocStore store;
    private final Clock clock;
    private final String instanceId;

    @org.springframework.beans.factory.annotation.Autowired
    public SchedulerLock(DocStore store, Clock clock) {
        this(store, clock, UUID.randomUUID().toString());
    }

    SchedulerLock(DocStore store, Clock clock, String instanceId) {
        this.store = store;
        this.clock = clock;
        this.instanceId = instanceId;
    }

    public static SchedulerLock forInstance(DocStore store, Clock clock, String instanceId) {
        return new SchedulerLock(store, clock, instanceId);
    }

    public String instanceId() {
        return instanceId;
    }

    /** Adquire ou renova a trava. Retorna false se outra instância a detém e ela ainda vale. */
    public boolean tryAcquire(String task, Duration ttl) {
        try {
            return store.transaction(tx -> {
                Instant now = Instant.now(clock);
                Lease current = tx.get(Collections.SCHEDULER_LOCKS, task, Lease.class).orElse(null);
                if (current != null && !current.owner().equals(instanceId) && current.until().isAfter(now)) {
                    return false;
                }
                tx.set(Collections.SCHEDULER_LOCKS, task, new Lease(task, instanceId, now.plus(ttl)));
                return true;
            });
        } catch (RuntimeException e) {
            log.warn("scheduler_lock_failed task={} err={}", task, e.toString());
            return false;
        }
    }
}
