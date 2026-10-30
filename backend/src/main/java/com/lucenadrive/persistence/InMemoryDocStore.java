package com.lucenadrive.persistence;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Implementação em memória usada em testes automatizados. Transações são serializadas por um
 * bloqueio global e aplicadas apenas no commit, reproduzindo a semântica tudo-ou-nada.
 * NÃO é usada como banco de dados em ambientes reais.
 */
public class InMemoryDocStore implements DocStore {

    private final Map<String, Map<String, Object>> docs = new ConcurrentHashMap<>();
    private final ReentrantLock txLock = new ReentrantLock(true);

    private static String path(String collection, String id) {
        return collection + "/" + id;
    }

    @Override
    public <T> Optional<T> get(String collection, String id, Class<T> type) {
        Map<String, Object> data = docs.get(path(collection, id));
        return Optional.ofNullable(data).map(d -> DocMapper.fromMap(d, type));
    }

    @Override
    public void set(String collection, String id, Object document) {
        txLock.lock();
        try {
            docs.put(path(collection, id), DocMapper.toMap(document));
        } finally {
            txLock.unlock();
        }
    }

    @Override
    public boolean createIfAbsent(String collection, String id, Object document) {
        txLock.lock();
        try {
            return docs.putIfAbsent(path(collection, id), DocMapper.toMap(document)) == null;
        } finally {
            txLock.unlock();
        }
    }

    @Override
    public void delete(String collection, String id) {
        txLock.lock();
        try {
            docs.remove(path(collection, id));
        } finally {
            txLock.unlock();
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void update(String collection, String id, Map<String, Object> fields) {
        txLock.lock();
        try {
            Map<String, Object> doc = docs.get(path(collection, id));
            if (doc == null) {
                throw new IllegalStateException("Documento inexistente: " + path(collection, id));
            }
            Map<String, Object> copy = DocMapper.toMap(doc);
            Map<String, Object> converted = DocMapper.toMap(fields);
            converted.forEach((field, value) -> {
                String[] parts = field.split("\\.");
                Map<String, Object> target = copy;
                for (int i = 0; i < parts.length - 1; i++) {
                    target = (Map<String, Object>) target.computeIfAbsent(parts[i], k -> new LinkedHashMap<>());
                }
                target.put(parts[parts.length - 1], value);
            });
            docs.put(path(collection, id), copy);
        } finally {
            txLock.unlock();
        }
    }

    @Override
    public <T> List<T> query(Query query, Class<T> type) {
        return run(query).map(d -> DocMapper.fromMap(d, type)).toList();
    }

    @Override
    public <T> void scan(Query query, Class<T> type, int pageSize, java.util.function.Consumer<T> consumer) {
        Query all = query.copyWithoutLimit();
        run(all).map(d -> DocMapper.fromMap(d, type)).forEach(consumer);
    }

    @Override
    public long count(Query query) {
        return run(query).count();
    }

    @Override
    public long sum(Query query, String field) {
        return run(query).map(d -> resolve(d, field))
                .filter(Number.class::isInstance)
                .mapToLong(v -> ((Number) v).longValue())
                .sum();
    }

    @Override
    public <R> R transaction(Function<Tx, R> work) {
        txLock.lock();
        try {
            MemTx tx = new MemTx();
            R result = work.apply(tx);
            tx.commit();
            return result;
        } finally {
            txLock.unlock();
        }
    }

    private Stream<Map<String, Object>> run(Query q) {
        String prefix = q.collection() + "/";
        Stream<Map<String, Object>> stream = docs.entrySet().stream()
                .filter(e -> e.getKey().startsWith(prefix) && e.getKey().indexOf('/', prefix.length()) < 0)
                .map(Map.Entry::getValue)
                .filter(d -> q.filters().stream().allMatch(f -> matches(d, f)));
        if (!q.orders().isEmpty()) {
            Comparator<Map<String, Object>> cmp = null;
            for (Query.Order o : q.orders()) {
                Comparator<Map<String, Object>> c = (a, b) -> compare(resolve(a, o.field()), resolve(b, o.field()));
                if (o.direction() == Query.Direction.DESC) {
                    c = c.reversed();
                }
                cmp = cmp == null ? c : cmp.thenComparing(c);
            }
            stream = stream.sorted(cmp);
            if (q.startAfter() != null) {
                Query.Order first = q.orders().get(0);
                stream = stream.filter(d -> {
                    int c = compare(resolve(d, first.field()), q.startAfter());
                    return first.direction() == Query.Direction.ASC ? c > 0 : c < 0;
                });
            }
        }
        if (q.limit() > 0) {
            stream = stream.limit(q.limit());
        }
        return stream;
    }

    private static boolean matches(Map<String, Object> doc, Query.Filter f) {
        Object v = resolve(doc, f.field());
        return switch (f.op()) {
            case EQ -> v != null && compare(v, f.value()) == 0;
            case NEQ -> v != null && compare(v, f.value()) != 0;
            case LT -> v != null && compare(v, f.value()) < 0;
            case LTE -> v != null && compare(v, f.value()) <= 0;
            case GT -> v != null && compare(v, f.value()) > 0;
            case GTE -> v != null && compare(v, f.value()) >= 0;
            case IN -> v != null && ((Collection<?>) f.value()).stream().anyMatch(x -> compare(v, x) == 0);
            case ARRAY_CONTAINS -> v instanceof Collection<?> c && c.stream().anyMatch(x -> compare(x, f.value()) == 0);
            case ARRAY_CONTAINS_ANY -> v instanceof Collection<?> c && c.stream()
                    .anyMatch(x -> ((Collection<?>) f.value()).stream().anyMatch(y -> compare(x, y) == 0));
        };
    }

    @SuppressWarnings("unchecked")
    static Object resolve(Map<String, Object> doc, String field) {
        Object current = doc;
        for (String part : field.split("\\.")) {
            if (!(current instanceof Map<?, ?> m)) {
                return null;
            }
            current = ((Map<String, Object>) m).get(part);
        }
        return current;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static int compare(Object a, Object b) {
        if (a == null || b == null) {
            return a == b ? 0 : (a == null ? -1 : 1);
        }
        if (a instanceof Number na && b instanceof Number nb) {
            return new BigDecimal(na.toString()).compareTo(new BigDecimal(nb.toString()));
        }
        if (a instanceof Comparable ca && a.getClass().equals(b.getClass())) {
            return ca.compareTo(b);
        }
        return Objects.equals(a, b) ? 0 : a.toString().compareTo(b.toString());
    }

    private final class MemTx implements Tx {
        private final Map<String, Map<String, Object>> writes = new LinkedHashMap<>();

        /** Reproduz a restrição do Firestore: nenhuma leitura depois da primeira escrita. */
        private void assertNoWritesYet() {
            if (!writes.isEmpty()) {
                throw new IllegalStateException("Firestore transactions require all reads to be executed before all writes");
            }
        }

        @Override
        public <T> Optional<T> get(String collection, String id, Class<T> type) {
            assertNoWritesYet();
            String p = path(collection, id);
            if (writes.containsKey(p)) {
                return Optional.ofNullable(writes.get(p)).map(d -> DocMapper.fromMap(d, type));
            }
            return InMemoryDocStore.this.get(collection, id, type);
        }

        @Override
        public <T> List<T> query(Query query, Class<T> type) {
            assertNoWritesYet();
            return InMemoryDocStore.this.query(query, type);
        }

        @Override
        public void set(String collection, String id, Object document) {
            writes.put(path(collection, id), DocMapper.toMap(document));
        }

        @Override
        public void create(String collection, String id, Object document) {
            String p = path(collection, id);
            if (docs.containsKey(p) || writes.get(p) != null) {
                throw new DuplicateDocumentException(p);
            }
            writes.put(p, DocMapper.toMap(document));
        }

        @Override
        public void delete(String collection, String id) {
            writes.put(path(collection, id), null);
        }

        void commit() {
            List<String> deletes = new ArrayList<>();
            writes.forEach((p, d) -> {
                if (d == null) {
                    deletes.add(p);
                } else {
                    docs.put(p, d);
                }
            });
            deletes.forEach(docs::remove);
        }
    }
}
