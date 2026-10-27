package com.lucenadrive.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.lucenadrive.persistence.InMemoryDocStore;
import com.lucenadrive.scheduling.SchedulerLock;
import com.lucenadrive.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SchedulerLockTest {

    @Test
    void onlyOneInstanceHoldsTheLeaseUntilItExpires() {
        InMemoryDocStore store = new InMemoryDocStore();
        MutableClock clock = new MutableClock(Instant.parse("2026-10-27T12:00:00Z"));
        SchedulerLock a = SchedulerLock.forInstance(store, clock, "a");
        SchedulerLock b = SchedulerLock.forInstance(store, clock, "b");

        assertThat(a.tryAcquire("dispatch", Duration.ofSeconds(30))).isTrue();
        assertThat(b.tryAcquire("dispatch", Duration.ofSeconds(30))).isFalse();
        assertThat(a.tryAcquire("dispatch", Duration.ofSeconds(30))).isTrue(); // renovação
        assertThat(b.tryAcquire("coupons", Duration.ofSeconds(30))).isTrue(); // tarefas independentes

        clock.advance(Duration.ofSeconds(31));
        assertThat(b.tryAcquire("dispatch", Duration.ofSeconds(30))).isTrue(); // assume após vencimento
        assertThat(a.tryAcquire("dispatch", Duration.ofSeconds(30))).isFalse();
    }
}
