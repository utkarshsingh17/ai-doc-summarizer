package com.utkarsh.ai_doc_qna.common.exception;

import com.openai.errors.InternalServerException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIRetryableException;
import com.openai.errors.RateLimitException;

/**
 * Wraps a failure talking to the model provider, classified as transient or not.
 *
 * <p>Spring AI 2.0's {@code TransientAiException}/{@code NonTransientAiException} live in the
 * optional {@code spring-ai-retry} artifact, which is not on the classpath. Underneath, Spring AI's
 * OpenAI support uses the {@code openai-java} SDK and lets its {@code com.openai.errors.*}
 * exceptions propagate, so the classification is done against those.
 */
public class AiServiceException extends RuntimeException {

    private final boolean transientFailure;

    public AiServiceException(String message, Throwable cause, boolean transientFailure) {
        super(message, cause);
        this.transientFailure = transientFailure;
    }

    /**
     * The SDK already retries transient failures internally (its {@code maxRetries} defaults to 2),
     * so anything reaching here has exhausted those attempts.
     */
    public static AiServiceException translate(String message, RuntimeException cause) {
        boolean isTransient = cause instanceof OpenAIIoException          // connection failure
                || cause instanceof RateLimitException                    // 429
                || cause instanceof InternalServerException               // 5xx
                || cause instanceof OpenAIRetryableException;
        return new AiServiceException(message, cause, isTransient);
    }

    public boolean isTransientFailure() {
        return transientFailure;
    }
}
