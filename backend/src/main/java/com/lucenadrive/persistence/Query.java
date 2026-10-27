package com.lucenadrive.persistence;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Especificação de consulta independente do banco. Suporta o subconjunto de operadores que o
 * Firestore executa com índices (igualdade, intervalo, IN, array-contains) e paginação por cursor.
 * Campos aninhados usam notação com ponto ("fare.finalCents").
 */
public final class Query {

    public enum Op { EQ, NEQ, LT, LTE, GT, GTE, IN, ARRAY_CONTAINS, ARRAY_CONTAINS_ANY }

    public enum Direction { ASC, DESC }

    public record Filter(String field, Op op, Object value) {
    }

    public record Order(String field, Direction direction) {
    }

    private final String collection;
    private final List<Filter> filters = new ArrayList<>();
    private final List<Order> orders = new ArrayList<>();
    private int limit = 0;
    private Object startAfter;

    private Query(String collection) {
        this.collection = collection;
    }

    public static Query of(String collection) {
        return new Query(collection);
    }

    public Query where(String field, Op op, Object value) {
        filters.add(new Filter(field, op, normalize(value)));
        return this;
    }

    public Query eq(String field, Object value) {
        return where(field, Op.EQ, value);
    }

    public Query orderBy(String field, Direction direction) {
        orders.add(new Order(field, direction));
        return this;
    }

    public Query limit(int limit) {
        this.limit = limit;
        return this;
    }

    /** Cursor: valor do primeiro campo de ordenação do último item da página anterior. */
    public Query startAfter(Object value) {
        this.startAfter = normalize(value);
        return this;
    }

    public String collection() {
        return collection;
    }

    public List<Filter> filters() {
        return filters;
    }

    public List<Order> orders() {
        return orders;
    }

    public int limit() {
        return limit;
    }

    public Object startAfter() {
        return startAfter;
    }

    /** Converte valores para a mesma representação usada no armazenamento. */
    static Object normalize(Object value) {
        if (value instanceof Enum<?> e) {
            return e.name();
        }
        if (value instanceof Instant i) {
            return i.toEpochMilli();
        }
        if (value instanceof Integer i) {
            return i.longValue();
        }
        if (value instanceof Collection<?> c) {
            return c.stream().map(Query::normalize).toList();
        }
        return value;
    }
}
