package com.utkarsh.ai_doc_qna.common;

import java.util.List;

public record ApiError(String code, String message, List<String> details) {
}
