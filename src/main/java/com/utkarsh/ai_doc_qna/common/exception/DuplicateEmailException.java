package com.utkarsh.ai_doc_qna.common.exception;

public class DuplicateEmailException extends RuntimeException {

    public DuplicateEmailException(String email) {
        super("An account with email %s already exists".formatted(email));
    }
}
