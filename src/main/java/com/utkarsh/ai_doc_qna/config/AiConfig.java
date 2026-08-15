package com.utkarsh.ai_doc_qna.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiConfig {

    /**
     * No {@code QuestionAnswerAdvisor} here on purpose. That advisor injects retrieved context
     * into the prompt but does not expose which chunks it used, which makes source citations
     * impossible. Retrieval is done explicitly in {@code RetrievalService} instead.
     *
     * <p>No chat-memory advisor either: every question is answered independently from the
     * document corpus, and registering a memory advisor would make a conversation id mandatory
     * on every call in Spring AI 2.0.
     */
    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .build();
    }
}
