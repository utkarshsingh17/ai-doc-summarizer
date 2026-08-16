package com.utkarsh.ai_doc_qna.auth.dto;

public record AuthResponse(String accessToken, String refreshToken, long expiresIn) {
}
