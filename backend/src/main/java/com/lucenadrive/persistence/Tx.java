package com.lucenadrive.persistence;

import java.util.List;
import java.util.Optional;

/**
 * Operações dentro de uma transação. Como no Firestore, todas as leituras devem ocorrer antes
 * das escritas, e a função pode ser reexecutada em caso de contenção (não produza efeitos colaterais
 * externos dentro dela).
 */
public interface Tx {

    <T> Optional<T> get(String collection, String id, Class<T> type);

    <T> List<T> query(Query query, Class<T> type);

    void set(String collection, String id, Object document);

    /** Cria o documento; a transação falha com {@link DuplicateDocumentException} se já existir. */
    void create(String collection, String id, Object document);

    void delete(String collection, String id);
}
