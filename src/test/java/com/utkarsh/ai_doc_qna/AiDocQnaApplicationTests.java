package com.utkarsh.ai_doc_qna;

import com.utkarsh.ai_doc_qna.support.StubAiConfiguration;
import com.utkarsh.ai_doc_qna.support.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * Context smoke test. Needs the containers and the stub models: the real context requires a
 * database with pgvector, an object store, and an OpenAI key.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, StubAiConfiguration.class})
@TestPropertySource(properties = {
		"spring.ai.openai.api-key=not-used-by-the-stub",
		"app.storage.bucket=context-test"
})
class AiDocQnaApplicationTests {

	@Test
	void contextLoads() {
	}

}
