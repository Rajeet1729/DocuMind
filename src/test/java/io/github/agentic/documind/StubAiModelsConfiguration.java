package io.github.agentic.documind;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.ArrayList;
import java.util.List;

/**
 * Offline stand-ins for the two Spring AI models the OpenAI starter would otherwise
 * auto-configure, so the full application context can start with <em>no</em>
 * {@code OPENAI_API_KEY} and no network. Used by {@link DocumindApplicationTests}, which
 * excludes the OpenAI auto-configurations and imports these beans instead.
 *
 * <p>Neither stub needs to produce meaningful output: a context-load test only starts
 * the beans. The {@link ChatModel} satisfies the auto-configured
 * {@code ChatClient.Builder} (and so the application's own {@code ChatClient}); the
 * {@link EmbeddingModel} satisfies the Elasticsearch vector store, whose only call
 * during startup is {@link EmbeddingModel#dimensions()} to size the index. Determinism
 * is not needed here — the ranking-sensitive assertions live in the adapter test that
 * has its own deterministic embedder.
 */
@TestConfiguration(proxyBeanMethods = false)
public class StubAiModelsConfiguration {

	/** Matches spring.ai.vectorstore.elasticsearch.dimensions in application.properties. */
	private static final int DIMENSIONS = 1536;

	@Bean
	ChatModel chatModel() {
		return prompt -> new ChatResponse(List.of(new Generation(new AssistantMessage("stub answer"))));
	}

	@Bean
	EmbeddingModel embeddingModel() {
		return new StubEmbeddingModel();
	}

	/** A fixed-vector embedder: enough to start the vector store, never asked to rank. */
	static final class StubEmbeddingModel implements EmbeddingModel {

		@Override
		public int dimensions() {
			return DIMENSIONS;
		}

		@Override
		public float[] embed(String text) {
			return new float[DIMENSIONS];
		}

		@Override
		public float[] embed(Document document) {
			return embed(document.getText());
		}

		@Override
		public List<float[]> embed(List<String> texts) {
			List<float[]> out = new ArrayList<>(texts.size());
			for (String text : texts) {
				out.add(embed(text));
			}
			return out;
		}

		@Override
		public List<float[]> embed(List<Document> documents, EmbeddingOptions options,
				BatchingStrategy batchingStrategy) {
			List<float[]> out = new ArrayList<>(documents.size());
			for (Document document : documents) {
				out.add(embed(document.getText()));
			}
			return out;
		}

		@Override
		public EmbeddingResponse call(EmbeddingRequest request) {
			List<Embedding> embeddings = new ArrayList<>();
			List<String> instructions = request.getInstructions();
			for (int i = 0; i < instructions.size(); i++) {
				embeddings.add(new Embedding(embed(instructions.get(i)), i));
			}
			return new EmbeddingResponse(embeddings);
		}
	}
}
