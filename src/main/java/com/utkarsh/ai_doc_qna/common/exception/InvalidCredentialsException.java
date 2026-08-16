package com.utkarsh.ai_doc_qna.common.exception;

/**
 * Thrown for both "no such email" and "wrong password" — the message deliberately does not say
 * which, so a login endpoint cannot be used to enumerate registered emails.
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("Invalid email or password");
    }
}
