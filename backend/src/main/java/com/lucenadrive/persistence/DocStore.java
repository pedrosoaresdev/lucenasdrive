package com.lucenadrive.persistence;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/** Porta de persistência de documentos. Implementações: Firestore (produção) e memória (testes). */
public interface DocStore {

    <T> Optional<T> get(String collection, String id, Class<T> type);

    void set(String collection, String id, Object document);

    /** Cria somente se não existir. Retorna false se o documento já existia. */
    boolean createIfAbsent(String collection, String id, Object document);

    void delete(String collection, String id);

    /**
     * Atualiza apenas os campos informados (notação com ponto para aninhados), sem sobrescrever o
     * restante do documento. Falha se o documento não existir.
     */
    void update(String collection, String id, java.util.Map<String, Object> fields);

    <T> List<T> query(Query query, Class<T> type);

    /**
     * Percorre TODOS os documentos da consulta em páginas (sem teto silencioso), na ordem da consulta.
     * Use para totais e saldos que precisam ser exatos. O limite da consulta, se houver, é ignorado.
     */
    <T> void scan(Query query, Class<T> type, int pageSize, java.util.function.Consumer<T> consumer);

    long count(Query query);

    long sum(Query query, String field);

    <R> R transaction(Function<Tx, R> work);
}
