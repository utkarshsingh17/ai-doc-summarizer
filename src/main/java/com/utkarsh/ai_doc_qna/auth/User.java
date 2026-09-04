package com.utkarsh.ai_doc_qna.auth;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A registered account. Owns zero or more {@code SourceDocument}s.
 *
 * <p>Immutable: nothing about a user changes after registration, so there is no mutation API to
 * guard the way {@code SourceDocument} needs one for its ingestion status.
 */
public record User(UUID id, String email, String passwordHash, Instant createdAt) {

    public User {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(email, "email");
        Objects.requireNonNull(passwordHash, "passwordHash");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public static User create(String email, String passwordHash) {
        return new User(UUID.randomUUID(), email, passwordHash, Instant.now());
    }
}
