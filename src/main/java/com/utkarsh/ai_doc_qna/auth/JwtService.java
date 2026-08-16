package com.utkarsh.ai_doc_qna.auth;

import com.utkarsh.ai_doc_qna.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.Map;

/**
 * Issues and validates the two JWT types this app uses: short-lived access tokens carried on
 * every request, and longer-lived refresh tokens presented only to {@code POST /auth/refresh}.
 *
 * <p>Refresh is stateless — validity rests entirely on the token's own signature and expiry, with
 * no server-side revocation list. A leaked refresh token stays usable until it expires. Adding
 * revocation would mean persisting a token/family table; not worth it until this app actually
 * needs it.
 */
@Service
public class JwtService {

    private static final String CLAIM_TYPE = "type";
    private static final String TYPE_ACCESS = "access";
    private static final String TYPE_REFRESH = "refresh";

    private final SecretKey key;
    private final AppProperties.Jwt config;

    public JwtService(AppProperties properties) {
        this.config = properties.jwt();
        this.key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(config.secret()));
    }

    public String generateAccessToken(UserPrincipal principal) {
        return generateToken(principal, TYPE_ACCESS, config.accessTokenExpiryMs());
    }

    public String generateRefreshToken(UserPrincipal principal) {
        return generateToken(principal, TYPE_REFRESH, config.refreshTokenExpiryMs());
    }

    public long accessTokenExpirySeconds() {
        return config.accessTokenExpiryMs() / 1000;
    }

    public long refreshTokenExpirySeconds() {
        return config.refreshTokenExpiryMs() / 1000;
    }

    public boolean isAccessTokenValid(String token, UserPrincipal principal) {
        return isTokenValid(token, principal, TYPE_ACCESS);
    }

    public boolean isRefreshTokenValid(String token, UserPrincipal principal) {
        return isTokenValid(token, principal, TYPE_REFRESH);
    }

    /**
     * Returns the subject (email) without checking token type, so the refresh flow can look the
     * user up before re-validating the token against the loaded principal.
     *
     * @throws JwtException if the token is malformed, expired, or has an invalid signature
     */
    public String extractUsername(String token) {
        return extractClaims(token).getSubject();
    }

    private boolean isTokenValid(String token, UserPrincipal principal, String expectedType) {
        try {
            Claims claims = extractClaims(token);
            return expectedType.equals(claims.get(CLAIM_TYPE, String.class))
                    && principal.getUsername().equals(claims.getSubject())
                    && claims.getExpiration().after(new Date());
        } catch (JwtException ex) {
            return false;
        }
    }

    private String generateToken(UserPrincipal principal, String type, long expiryMs) {
        Date now = new Date();
        return Jwts.builder()
                .claims(Map.of(CLAIM_TYPE, type))
                .subject(principal.getUsername())
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expiryMs))
                .signWith(key)
                .compact();
    }

    private Claims extractClaims(String token) {
        return Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload();
    }
}
