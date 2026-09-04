package com.utkarsh.ai_doc_qna;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.data.web.config.EnableSpringDataWebSupport;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * {@code @EnableSpringDataWebSupport} is explicit, not left to auto-configuration: without a
 * Spring Data repository starter on the classpath (there is none — Qdrant is not a Spring Data
 * module), {@code SpringDataWebAutoConfiguration} does not activate on its own, and
 * {@code @PageableDefault Pageable} parameters silently fall back to being bound like a request
 * DTO instead of resolved from {@code ?page=&size=&sort=}.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableAsync
@EnableSpringDataWebSupport
public class AiDocQnaApplication {

	public static void main(String[] args) {
		SpringApplication.run(AiDocQnaApplication.class, args);
	}

}
