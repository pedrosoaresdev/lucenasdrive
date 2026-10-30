package com.lucenadrive.persistence;

import com.google.api.core.ApiFuture;
import com.google.api.gax.rpc.AlreadyExistsException;
import com.google.cloud.firestore.AggregateField;
import com.google.cloud.firestore.AggregateQuerySnapshot;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.FieldPath;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.Transaction;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/** Implementação de {@link DocStore} sobre o Cloud Firestore (Admin SDK). */
public class FirestoreDocStore implements DocStore {

    private static final long TIMEOUT_SECONDS = 15;

    private final Firestore db;

    public FirestoreDocStore(Firestore db) {
        this.db = db;
    }

    @Override
    public <T> Optional<T> get(String collection, String id, Class<T> type) {
        DocumentSnapshot snap = await(db.collection(collection).document(id).get());
        return toObject(snap, type);
    }

    @Override
    public void set(String collection, String id, Object document) {
        await(db.collection(collection).document(id).set(DocMapper.toMap(document)));
    }

    @Override
    public boolean createIfAbsent(String collection, String id, Object document) {
        try {
            await(db.collection(collection).document(id).create(DocMapper.toMap(document)));
            return true;
        } catch (PersistenceException e) {
            if (e.getCause() instanceof AlreadyExistsException) {
                return false;
            }
            throw e;
        }
    }

    @Override
    public void delete(String collection, String id) {
        await(db.collection(collection).document(id).delete());
    }

    @Override
    public void update(String collection, String id, Map<String, Object> fields) {
        Map<String, Object> converted = DocMapper.toMap(fields);
        await(db.collection(collection).document(id).update(converted));
    }

    @Override
    public <T> List<T> query(Query query, Class<T> type) {
        return await(build(query).get()).getDocuments().stream()
                .map(d -> DocMapper.fromMap(d.getData(), type))
                .toList();
    }

    @Override
    public <T> void scan(Query query, Class<T> type, int pageSize, java.util.function.Consumer<T> consumer) {
        Query q = query.copyWithoutLimit();
        com.google.cloud.firestore.Query base = build(q);
        if (q.orders().isEmpty()) {
            // Ordem estável para o cursor: campo de desigualdade (exigido pelo Firestore) e depois o ID do documento.
            String ineq = q.filters().stream().filter(f -> f.op() == Query.Op.LT || f.op() == Query.Op.LTE
                    || f.op() == Query.Op.GT || f.op() == Query.Op.GTE || f.op() == Query.Op.NEQ)
                    .map(Query.Filter::field).findFirst().orElse(null);
            if (ineq != null) {
                base = base.orderBy(FieldPath.of(ineq.split("\\.")));
            }
            base = base.orderBy(FieldPath.documentId());
        }
        DocumentSnapshot last = null;
        while (true) {
            com.google.cloud.firestore.Query page = base.limit(Math.max(1, pageSize));
            if (last != null) {
                page = page.startAfter(last);
            }
            List<QueryDocumentSnapshot> docs = await(page.get()).getDocuments();
            for (QueryDocumentSnapshot d : docs) {
                consumer.accept(DocMapper.fromMap(d.getData(), type));
            }
            if (docs.size() < pageSize) {
                return;
            }
            last = docs.get(docs.size() - 1);
        }
    }

    @Override
    public long count(Query query) {
        return await(build(query).count().get()).getCount();
    }

    @Override
    public long sum(Query query, String field) {
        AggregateField sum = AggregateField.sum(field);
        AggregateQuerySnapshot snap = await(build(query).aggregate(sum).get());
        Number value = (Number) snap.get(sum);
        return value == null ? 0 : value.longValue();
    }

    @Override
    public <R> R transaction(Function<Tx, R> work) {
        try {
            return await(db.runTransaction(t -> work.apply(new FsTx(t))));
        } catch (PersistenceException e) {
            if (e.getCause() instanceof RuntimeException re && !(re instanceof PersistenceException)) {
                throw re;
            }
            throw e;
        }
    }

    private com.google.cloud.firestore.Query build(Query q) {
        com.google.cloud.firestore.Query fq = db.collection(q.collection());
        for (Query.Filter f : q.filters()) {
            FieldPath path = FieldPath.of(f.field().split("\\."));
            fq = switch (f.op()) {
                case EQ -> fq.whereEqualTo(path, f.value());
                case NEQ -> fq.whereNotEqualTo(path, f.value());
                case LT -> fq.whereLessThan(path, f.value());
                case LTE -> fq.whereLessThanOrEqualTo(path, f.value());
                case GT -> fq.whereGreaterThan(path, f.value());
                case GTE -> fq.whereGreaterThanOrEqualTo(path, f.value());
                case IN -> fq.whereIn(path, List.copyOf((Collection<?>) f.value()));
                case ARRAY_CONTAINS -> fq.whereArrayContains(path, f.value());
                case ARRAY_CONTAINS_ANY -> fq.whereArrayContainsAny(path, List.copyOf((Collection<?>) f.value()));
            };
        }
        for (Query.Order o : q.orders()) {
            fq = fq.orderBy(FieldPath.of(o.field().split("\\.")), o.direction() == Query.Direction.ASC
                    ? com.google.cloud.firestore.Query.Direction.ASCENDING
                    : com.google.cloud.firestore.Query.Direction.DESCENDING);
        }
        if (q.startAfter() != null && !q.orders().isEmpty()) {
            fq = fq.startAfter(q.startAfter());
        }
        if (q.limit() > 0) {
            fq = fq.limit(q.limit());
        }
        return fq;
    }

    private static <T> Optional<T> toObject(DocumentSnapshot snap, Class<T> type) {
        if (!snap.exists()) {
            return Optional.empty();
        }
        Map<String, Object> data = snap.getData();
        return Optional.of(DocMapper.fromMap(data, type));
    }

    private static <V> V await(ApiFuture<V> future) {
        try {
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PersistenceException(e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            // Exceções de negócio lançadas dentro de transações chegam embrulhadas.
            while (cause instanceof ExecutionException && cause.getCause() != null) {
                cause = cause.getCause();
            }
            if (cause instanceof com.lucenadrive.common.error.ApiException api) {
                throw api;
            }
            if (cause instanceof DuplicateDocumentException dup) {
                throw dup;
            }
            throw new PersistenceException(cause);
        } catch (TimeoutException e) {
            throw new PersistenceException(e);
        }
    }

    private final class FsTx implements Tx {
        private final Transaction t;

        FsTx(Transaction t) {
            this.t = t;
        }

        @Override
        public <T> Optional<T> get(String collection, String id, Class<T> type) {
            return toObject(await(t.get(db.collection(collection).document(id))), type);
        }

        @Override
        public <T> List<T> query(Query query, Class<T> type) {
            return await(t.get(build(query))).getDocuments().stream()
                    .map(d -> DocMapper.fromMap(d.getData(), type))
                    .toList();
        }

        @Override
        public void set(String collection, String id, Object document) {
            t.set(db.collection(collection).document(id), DocMapper.toMap(document));
        }

        @Override
        public void create(String collection, String id, Object document) {
            t.create(db.collection(collection).document(id), DocMapper.toMap(document));
        }

        @Override
        public void delete(String collection, String id) {
            t.delete(db.collection(collection).document(id));
        }
    }

    public static class PersistenceException extends RuntimeException {
        public PersistenceException(Throwable cause) {
            super(cause);
        }
    }
}
