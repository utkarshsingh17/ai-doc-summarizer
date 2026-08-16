package com.utkarsh.ai_doc_qna.support;

import com.utkarsh.ai_doc_qna.auth.UserRepository;
import jakarta.servlet.http.Cookie;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Registers a throwaway user and returns both the {@code access_token} cookie register/login set
 * and the user's id, so full-context integration tests can drive the real, secured HTTP
 * endpoints — and, where a test calls a service bean directly rather than through HTTP, scope
 * that call to the same user — without duplicating the register/login dance in every test class.
 */
public final class AuthTestSupport {

    private AuthTestSupport() {
    }

    public record Registered(Cookie accessCookie, UUID userId) {
    }

    /** A random email per call avoids collisions between test methods sharing one database. */
    public static Registered register(MockMvc mockMvc, UserRepository userRepository) throws Exception {
        String email = "test-" + UUID.randomUUID() + "@example.com";
        MockHttpServletResponse response = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"password123\"}".formatted(email)))
                .andExpect(status().isCreated())
                .andReturn().getResponse();
        Cookie accessCookie = response.getCookie("access_token");
        UUID userId = userRepository.findByEmail(email).orElseThrow().getId();
        return new Registered(accessCookie, userId);
    }
}
