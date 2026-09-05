package com.utkarsh.ai_doc_qna.common;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Unauthenticated liveness probe for the hosting platform (Render's health check hits this
 * before routing traffic to a new instance). Deliberately outside {@code /api/v1} and outside
 * {@link ApiResponse} — a host's health checker wants a plain 200, not a JSON envelope to parse.
 */
@RestController
public class HealthController {

    @GetMapping("/healthz")
    public String healthz() {
        return "OK";
    }
}
