package com.utkarsh.ai_doc_qna.auth;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The authenticated principal carried in {@code SecurityContext} and resolved via
 * {@code @AuthenticationPrincipal}.
 *
 * <p>Deliberately independent of the {@link User} entity — it needs to exist before the entity
 * has a generated id (issuing tokens right after registration) and needs to be trivially
 * constructible in tests without touching JPA.
 */
public final class UserPrincipal implements UserDetails {

    private final UUID id;
    private final String email;
    private final String passwordHash;

    public UserPrincipal(UUID id, String email, String passwordHash) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
    }

    public static UserPrincipal of(User user) {
        return new UserPrincipal(user.getId(), user.getEmail(), user.getPasswordHash());
    }

    public UUID getId() {
        return id;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_USER"));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }
}
