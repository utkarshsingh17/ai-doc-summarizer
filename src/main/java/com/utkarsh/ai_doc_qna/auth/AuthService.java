package com.utkarsh.ai_doc_qna.auth;

import com.utkarsh.ai_doc_qna.auth.dto.AuthResponse;
import com.utkarsh.ai_doc_qna.auth.dto.LoginRequest;
import com.utkarsh.ai_doc_qna.auth.dto.RegisterRequest;
import com.utkarsh.ai_doc_qna.common.exception.DuplicateEmailException;
import com.utkarsh.ai_doc_qna.common.exception.InvalidCredentialsException;
import com.utkarsh.ai_doc_qna.common.exception.InvalidTokenException;
import io.jsonwebtoken.JwtException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final UserRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository repository, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public AuthResponse register(RegisterRequest request) {
        String email = normalize(request.email());
        if (repository.existsByEmail(email)) {
            throw new DuplicateEmailException(email);
        }
        User user = repository.save(User.create(email, passwordEncoder.encode(request.password())));
        return issueTokens(UserPrincipal.of(user));
    }

    public AuthResponse login(LoginRequest request) {
        User user = repository.findByEmail(normalize(request.email()))
                .orElseThrow(InvalidCredentialsException::new);
        if (!passwordEncoder.matches(request.password(), user.passwordHash())) {
            throw new InvalidCredentialsException();
        }
        return issueTokens(UserPrincipal.of(user));
    }

    /**
     * Stateless rotation: a new pair is issued, but the presented refresh token is not
     * invalidated server-side. See {@link JwtService} for why.
     */
    public AuthResponse refresh(String refreshToken) {
        String email;
        try {
            email = jwtService.extractUsername(refreshToken);
        } catch (JwtException ex) {
            throw new InvalidTokenException("Refresh token is invalid or expired");
        }
        User user = repository.findByEmail(email)
                .orElseThrow(() -> new InvalidTokenException("Refresh token is invalid or expired"));
        UserPrincipal principal = UserPrincipal.of(user);
        if (!jwtService.isRefreshTokenValid(refreshToken, principal)) {
            throw new InvalidTokenException("Refresh token is invalid or expired");
        }
        return issueTokens(principal);
    }

    private AuthResponse issueTokens(UserPrincipal principal) {
        return new AuthResponse(
                jwtService.generateAccessToken(principal),
                jwtService.generateRefreshToken(principal),
                jwtService.accessTokenExpirySeconds());
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase();
    }
}
