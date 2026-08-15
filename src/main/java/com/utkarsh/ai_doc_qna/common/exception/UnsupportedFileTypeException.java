package com.utkarsh.ai_doc_qna.common.exception;

import java.util.Collection;

public class UnsupportedFileTypeException extends RuntimeException {

    public UnsupportedFileTypeException(String contentType, Collection<String> supported) {
        super("Unsupported content type '%s'. Supported types: %s".formatted(contentType, String.join(", ", supported)));
    }
}
