package com.utkarsh.ai_doc_qna.common.exception;

import java.util.UUID;

public class DocumentNotFoundException extends RuntimeException {

    public DocumentNotFoundException(UUID id) {
        super("Document %s not found".formatted(id));
    }
}
