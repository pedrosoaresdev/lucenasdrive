package com.lucenadrive.persistence;

public class DuplicateDocumentException extends RuntimeException {

    public DuplicateDocumentException(String path) {
        super("Documento já existe: " + path);
    }
}
