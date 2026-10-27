package com.lucenadrive.support;

import com.lucenadrive.persistence.DocStore;
import com.lucenadrive.persistence.InMemoryDocStore;
import java.time.Instant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Substitui Firebase/Firestore por implementações em memória nos testes de integração. */
@TestConfiguration
public class TestBeans {

    @Bean
    @Primary
    public MutableClock testClock() {
        return new MutableClock(Instant.parse("2026-10-20T15:00:00Z"));
    }

    @Bean
    public DocStore docStore() {
        return new InMemoryDocStore();
    }

    @Bean
    public FakeIdTokenVerifier idTokenVerifier(MutableClock clock) {
        return new FakeIdTokenVerifier(clock);
    }

    @Bean
    public FakeAuthAdmin authAdmin(FakeIdTokenVerifier verifier) {
        return new FakeAuthAdmin(verifier);
    }

    @Bean
    public FakeObjectStorage objectStorage() {
        return new FakeObjectStorage();
    }
}
