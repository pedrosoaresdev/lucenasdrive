package com.lucenadrive.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.lucenadrive.persistence.InMemoryDocStore;
import com.lucenadrive.persistence.Query;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ScanTest {

    @Test
    void inMemoryScanIgnoresLimitAndReturnsEverything() {
        InMemoryDocStore store = new InMemoryDocStore();
        for (int i = 0; i < 30; i++) {
            store.set("x", "d" + i, Map.of("k", "a", "n", i));
        }
        List<Map> out = new ArrayList<>();
        store.scan(Query.of("x").eq("k", "a").limit(5), Map.class, 7, out::add);
        assertThat(out).hasSize(30);
    }
}
