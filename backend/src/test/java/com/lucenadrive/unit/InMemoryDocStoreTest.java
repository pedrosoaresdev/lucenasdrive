package com.lucenadrive.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lucenadrive.persistence.DuplicateDocumentException;
import com.lucenadrive.persistence.InMemoryDocStore;
import com.lucenadrive.persistence.Query;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InMemoryDocStoreTest {

    record Doc(String id, String status, long n, Instant at, List<String> tags, Map<String, Object> nested) {
    }

    @Test
    void queriesFilterOrderPaginateAndAggregate() {
        InMemoryDocStore s = new InMemoryDocStore();
        for (int i = 0; i < 5; i++) {
            s.set("docs", "d" + i, new Doc("d" + i, i % 2 == 0 ? "A" : "B", i, Instant.ofEpochMilli(1000L * i),
                    List.of("t" + i), Map.of("v", i)));
        }
        s.set("docs/d0/sub", "x", new Doc("x", "A", 99, Instant.EPOCH, List.of(), Map.of()));
        var page1 = s.query(Query.of("docs").eq("status", "A").orderBy("at", Query.Direction.DESC).limit(2), Doc.class);
        assertThat(page1).extracting(Doc::id).containsExactly("d4", "d2");
        var page2 = s.query(Query.of("docs").eq("status", "A").orderBy("at", Query.Direction.DESC).limit(2)
                .startAfter(page1.get(1).at()), Doc.class);
        assertThat(page2).extracting(Doc::id).containsExactly("d0");
        assertThat(s.count(Query.of("docs").where("nested.v", Query.Op.GTE, 3))).isEqualTo(2);
        assertThat(s.sum(Query.of("docs"), "n")).isEqualTo(10);
        assertThat(s.count(Query.of("docs").where("tags", Query.Op.ARRAY_CONTAINS, "t3"))).isEqualTo(1);
        assertThat(s.count(Query.of("docs").where("status", Query.Op.IN, List.of("A", "B")))).isEqualTo(5);
    }

    @Test
    void transactionsAreAllOrNothing() {
        InMemoryDocStore s = new InMemoryDocStore();
        s.set("c", "1", Map.of("v", 1));
        assertThatThrownBy(() -> s.transaction(tx -> {
            tx.set("c", "2", Map.of("v", 2));
            tx.create("c", "1", Map.of("v", 3));
            return null;
        })).isInstanceOf(DuplicateDocumentException.class);
        assertThat(s.get("c", "2", Map.class)).isEmpty();
        assertThat(s.createIfAbsent("c", "1", Map.of())).isFalse();
    }

    @Test
    void rejectsReadsAfterWritesLikeFirestore() {
        InMemoryDocStore s = new InMemoryDocStore();
        assertThatThrownBy(() -> s.transaction(tx -> {
            tx.set("c", "1", Map.of("v", 1));
            return tx.get("c", "2", Map.class);
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("reads to be executed before all writes");
    }

    @Test
    void partialUpdateMergesNestedFields() {
        InMemoryDocStore s = new InMemoryDocStore();
        s.set("c", "1", Map.of("status", "X", "pos", Map.of("lat", 1.0)));
        s.update("c", "1", Map.of("pos.lng", 2.0));
        @SuppressWarnings("unchecked")
        Map<String, Object> doc = s.get("c", "1", Map.class).orElseThrow();
        assertThat(doc).containsEntry("status", "X");
        @SuppressWarnings("unchecked")
        Map<String, Object> pos = (Map<String, Object>) doc.get("pos");
        assertThat(pos).containsEntry("lat", 1.0).containsEntry("lng", 2.0);
    }
}
