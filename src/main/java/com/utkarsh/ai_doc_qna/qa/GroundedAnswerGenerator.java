package com.utkarsh.ai_doc_qna.qa;

import com.openai.errors.OpenAIException;
import com.utkarsh.ai_doc_qna.common.exception.AiServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * Wraps the single chat call that turns retrieved excerpts into a grounded answer.
 *
 * <p>No {@code @Retryable} here: the underlying {@code openai-java} client already retries
 * connection failures, 429s and 5xx internally (its {@code maxRetries} defaults to 2). Adding a
 * second retry layer would multiply out to nine HTTP calls for one question and hide the latency.
 */
@Component
public class GroundedAnswerGenerator {

    private static final Logger log = LoggerFactory.getLogger(GroundedAnswerGenerator.class);

    private final ChatClient chatClient;
    private final Resource promptTemplate;

    public GroundedAnswerGenerator(ChatClient chatClient,
                                   @Value("classpath:prompts/grounded-answer.st") Resource promptTemplate) {
        this.chatClient = chatClient;
        this.promptTemplate = promptTemplate;
    }

    public GroundedAnswer generate(String question, String context) {
        try {
            ResponseEntity<ChatResponse, GroundedAnswer> response = chatClient.prompt()
                    .user(user -> user.text(promptTemplate)
                            .param("context", context)
                            .param("question", question))
                    .call()
                    .responseEntity(GroundedAnswer.class);

            logUsage(response.getResponse());
            GroundedAnswer answer = response.entity();
            if (answer == null) {
                throw new AiServiceException("Model returned no parseable answer", null, false);
            }
            return answer;
        } catch (OpenAIException ex) {
            throw AiServiceException.translate("Chat request to the model provider failed", ex);
        }
    }

    private void logUsage(ChatResponse response) {
        if (response == null || response.getMetadata() == null) {
            return;
        }
        Usage usage = response.getMetadata().getUsage();
        if (usage != null) {
            log.debug("Chat call used {} prompt + {} completion = {} total tokens",
                    usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens());
        }
    }
}
