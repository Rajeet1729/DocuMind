package io.github.agentic.documind.adapter.out.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import io.github.agentic.documind.TestcontainersConfiguration;
import io.github.agentic.documind.application.port.out.ChunkStore;
import io.github.agentic.documind.domain.Chunk;
import io.github.agentic.documind.domain.Provenance;
import io.github.agentic.documind.domain.RetrievedChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for {@link ElasticsearchChunkStore} against a real Elasticsearch
 * (Testcontainers), but with a <em>deterministic, offline</em> {@link EmbeddingModel}
 * standing in for OpenAI. The OpenAI auto-configurations are excluded, so this test
 * needs no {@code OPENAI_API_KEY} and makes no network calls — the only moving part is
 * the ES container, and the ranking is reproducible run to run.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                // Deterministic + offline: no OpenAI beans, so no API key and no network.
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
@Import(TestcontainersConfiguration.class)
class ElasticsearchChunkStoreTest {

    private static final Chunk FRANCE_CAPITAL =
            new Chunk("The capital of France is Paris.",
                    new Provenance("doc-france", "france.pdf", 1, 0));
    private static final Chunk FRANCE_LANDMARK =
            new Chunk("The Eiffel Tower is a famous landmark in Paris.",
                    new Provenance("doc-france", "france.pdf", 2, 1));
    private static final Chunk GERMANY_CAPITAL =
            new Chunk("The capital of Germany is Berlin.",
                    new Provenance("doc-germany", "germany.pdf", 1, 0));
    private static final Chunk UNRELATED =
            new Chunk("Bananas are a yellow tropical fruit rich in potassium.",
                    new Provenance("doc-germany", "germany.pdf", 3, 1));

    @Autowired
    ChunkStore chunkStore;

    @Autowired
    VectorStore vectorStore;

    @BeforeEach
    void seed() throws Exception {
        chunkStore.save(List.of(FRANCE_CAPITAL, FRANCE_LANDMARK, GERMANY_CAPITAL, UNRELATED));
        // The ES store does not refresh on write; force it so writes are searchable now.
        vectorStore.<ElasticsearchClient>getNativeClient().orElseThrow().indices().refresh();
    }

    @Test
    void ranksMostRelevantFirstAndRoundTripsProvenanceExactly() {
        List<RetrievedChunk> results = chunkStore.search("capital of France", 4);

        assertThat(results).isNotEmpty();

        RetrievedChunk top = results.get(0);
        assertThat(top.chunk().text()).isEqualTo(FRANCE_CAPITAL.text());
        // Provenance survives the write/read round-trip byte-for-byte (records' equals).
        assertThat(top.chunk().provenance()).isEqualTo(FRANCE_CAPITAL.provenance());

        // Scores come back most-relevant-first and are never negative (cosine of
        // non-negative bag-of-words vectors; a zero-overlap chunk scores exactly 0).
        assertThat(results).isSortedAccordingTo(
                (a, b) -> Double.compare(b.score(), a.score()));
        assertThat(results).allSatisfy(r -> assertThat(r.score()).isGreaterThanOrEqualTo(0.0));
        assertThat(top.score()).isPositive();

        // The France-capital chunk out-ranks the Germany-capital chunk for a France query.
        RetrievedChunk germanyCapital = results.stream()
                .filter(r -> r.chunk().provenance().equals(GERMANY_CAPITAL.provenance()))
                .findFirst()
                .orElseThrow();
        assertThat(top.score()).isGreaterThan(germanyCapital.score());
    }

    @Test
    void respectsTopK() {
        assertThat(chunkStore.search("capital", 1)).hasSize(1);
        assertThat(chunkStore.search("capital", 2)).hasSize(2);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeEmbeddingConfig {

        // 1536 matches spring.ai.vectorstore.elasticsearch.dimensions in application.properties.
        @Bean
        EmbeddingModel embeddingModel() {
            return new DeterministicEmbeddingModel(1536);
        }
    }

    /**
     * A hashing-trick bag-of-words embedding: each token bumps one dimension, then the
     * vector is L2-normalized. Cosine similarity therefore tracks token overlap, which
     * is enough to give a stable, inspectable ranking without any model call.
     */
    static final class DeterministicEmbeddingModel implements EmbeddingModel {

        private final int dimensions;

        DeterministicEmbeddingModel(int dimensions) {
            this.dimensions = dimensions;
        }

        @Override
        public int dimensions() {
            return dimensions;
        }

        @Override
        public float[] embed(String text) {
            float[] vector = new float[dimensions];
            for (String token : text.toLowerCase().split("[^a-z0-9]+")) {
                if (token.isEmpty()) {
                    continue;
                }
                // String.hashCode() is spec-defined, so this is stable across JVMs.
                int bucket = Math.floorMod(token.hashCode(), dimensions);
                vector[bucket] += 1.0f;
            }
            double norm = 0.0;
            for (float x : vector) {
                norm += (double) x * x;
            }
            norm = Math.sqrt(norm);
            if (norm == 0.0) {
                return vector; // empty text -> zero vector; tests never embed empty text.
            }
            for (int i = 0; i < vector.length; i++) {
                vector[i] /= (float) norm;
            }
            return vector;
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
            List<String> instructions = request.getInstructions();
            List<Embedding> embeddings = new ArrayList<>(instructions.size());
            for (int i = 0; i < instructions.size(); i++) {
                embeddings.add(new Embedding(embed(instructions.get(i)), i));
            }
            return new EmbeddingResponse(embeddings);
        }
    }
}
