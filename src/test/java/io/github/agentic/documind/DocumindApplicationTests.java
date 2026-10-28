package io.github.agentic.documind;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Whole-application context-load smoke test. It starts the real {@link DocumindApplication}
 * context end to end, but deterministically and offline:
 *
 * <ul>
 *   <li>the OpenAI auto-configurations are excluded, so no {@code OPENAI_API_KEY} is read
 *       and no OpenAI endpoint is contacted;</li>
 *   <li>{@link StubAiModelsConfiguration} supplies the two beans those auto-configs would
 *       have — a stub {@code ChatModel} (which the auto-configured {@code ChatClient.Builder}
 *       and the app's {@code ChatClient} depend on) and a stub {@code EmbeddingModel}
 *       (which the Elasticsearch vector store depends on);</li>
 *   <li>Elasticsearch is a Testcontainers instance wired in via {@code @ServiceConnection},
 *       so the one live dependency is Docker, not a paid API. Docker Compose is disabled so
 *       the two do not both try to start a store.</li>
 * </ul>
 *
 * The suite therefore goes green on CI without any secret or outbound network call.
 */
@SpringBootTest(properties = {
		// No OpenAI beans -> no API key, no network. Same exclusion list as the ES adapter test.
		"spring.autoconfigure.exclude="
				+ "org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration,"
				+ "org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration,"
				+ "org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration,"
				+ "org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration,"
				+ "org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration,"
				+ "org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration",
		// Testcontainers already provides ES; don't also start Docker Compose.
		"spring.docker.compose.enabled=false"
})
@Import({ TestcontainersConfiguration.class, StubAiModelsConfiguration.class })
class DocumindApplicationTests {

	@Test
	void contextLoads() {
	}

}
