package com.utkarsh.ai_doc_qna.auth;

import com.utkarsh.ai_doc_qna.auth.dto.AuthResponse;
import com.utkarsh.ai_doc_qna.auth.dto.LoginRequest;
import com.utkarsh.ai_doc_qna.auth.dto.RegisterRequest;
import com.utkarsh.ai_doc_qna.common.ApiResponse;
import com.utkarsh.ai_doc_qna.config.AppProperties;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * Tokens travel exclusively as {@code HttpOnly} cookies, never in the response body — an XSS bug
 * or an access log can't leak what was never JS-readable or logged as JSON. The access cookie is
 * sent on every request ({@code Path=/}); the refresh cookie is scoped to this controller's own
 * refresh endpoint ({@code Path=/api/v1/auth/refresh}) so it is never attached to, or at risk on,
 * an ordinary API call.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final String ACCESS_COOKIE = "access_token";
    private static final String REFRESH_COOKIE = "refresh_token";
    private static final String REFRESH_PATH = "/api/v1/auth/refresh";

    private final AuthService authService;
    private final JwtService jwtService;
    private final boolean cookieSecure;

    public AuthController(AuthService authService, JwtService jwtService, AppProperties properties) {
        this.authService = authService;
        this.jwtService = jwtService;
        this.cookieSecure = properties.jwt().cookieSecure();
    }

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<Void>> register(@Valid @RequestBody RegisterRequest request) {
        return withAuthCookies(authService.register(request), HttpStatus.CREATED);
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<Void>> login(@Valid @RequestBody LoginRequest request) {
        return withAuthCookies(authService.login(request), HttpStatus.OK);
    }

    /**
     * No request body: the refresh token is the {@code refresh_token} cookie, scoped so the
     * browser only sends it here. A missing cookie means "not logged in", mapped to 401 by
     * {@code GlobalExceptionHandler}'s handler for {@code MissingRequestCookieException}.
     */
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<Void>> refresh(@CookieValue(REFRESH_COOKIE) String refreshToken) {
        return withAuthCookies(authService.refresh(refreshToken), HttpStatus.OK);
    }

    /**
     * HttpOnly cookies can't be cleared by client-side JS, so logging out needs a server round
     * trip that overwrites both cookies with an immediately-expired one.
     */
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout() {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, expiredCookie(ACCESS_COOKIE, "/").toString())
                .header(HttpHeaders.SET_COOKIE, expiredCookie(REFRESH_COOKIE, REFRESH_PATH).toString())
                .body(ApiResponse.ok(null));
    }

    private ResponseEntity<ApiResponse<Void>> withAuthCookies(AuthResponse tokens, HttpStatus status) {
        ResponseCookie access = ResponseCookie.from(ACCESS_COOKIE, tokens.accessToken())
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ofSeconds(jwtService.accessTokenExpirySeconds()))
                .build();
        ResponseCookie refresh = ResponseCookie.from(REFRESH_COOKIE, tokens.refreshToken())
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Lax")
                .path(REFRESH_PATH)
                .maxAge(Duration.ofSeconds(jwtService.refreshTokenExpirySeconds()))
                .build();
        return ResponseEntity.status(status)
                .header(HttpHeaders.SET_COOKIE, access.toString())
                .header(HttpHeaders.SET_COOKIE, refresh.toString())
                .body(ApiResponse.ok(null));
    }

    private ResponseCookie expiredCookie(String name, String path) {
        return ResponseCookie.from(name, "")
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Lax")
                .path(path)
                .maxAge(0)
                .build();
    }
}
