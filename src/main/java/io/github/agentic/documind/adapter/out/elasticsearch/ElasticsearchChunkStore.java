package io.github.agentic.documind.adapter.out.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import io.github.agentic.documind.application.port.out.ChunkStore;
import io.github.agentic.documind.domain.Chunk;
import io.github.agentic.documind.domain.Provenance;
import io.github.agentic.documind.domain.RetrievedChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Elasticsearch-backed {@link ChunkStore}, built on the Spring AI {@link VectorStore}.
 *
 * <p>This adapter is the single home of the {@link Provenance} &harr; vector-store
 * metadata mapping. The same four keys are written in {@link #save} and read back in
 * {@link #search}, so a citation's provenance round-trips exactly.
 *
 * <p><b>Retrieval has two modes behind this one seam</b> (Slice 3). By default it is
 * vector-only: the Spring AI {@link VectorStore} embeds the query and runs kNN. With
 * {@code documind.retrieval.hybrid.enabled=true} it becomes <b>hybrid</b> — a lexical
 * BM25 query and a vector kNN query are run against Elasticsearch and fused with
 * Reciprocal Rank Fusion (RRF) in-process. (ES-native RRF is an Enterprise-licensed
 * feature and returns 403 on the basic license, so the fusion is done here over two
 * basic-license queries.) Nothing upstream changes: callers still get the same
 * {@link ChunkStore#search} contract, just better-ordered results. The toggle keeps the
 * off path byte-identical to the pre-Slice-3 baseline for a clean A/B.
 */
@Repository
public class ElasticsearchChunkStore implements ChunkStore {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchChunkStore.class);

    // The one definition of the Chunk-provenance <-> ES-metadata mapping. Applied
    // identically on write (save) and read (search).
    static final String META_DOCUMENT_ID = "documentId";
    static final String META_FILENAME = "filename";
    static final String META_PAGE_NUMBER = "pageNumber";
    static final String META_CHUNK_INDEX = "chunkIndex";

    // Spring AI ElasticsearchVectorStore index schema (verified against the live mapping):
    // the chunk text lives in "content", the dense vector in "embedding", and the chunk
    // metadata (the four keys above) is a nested object under "metadata".
    static final String FIELD_CONTENT = "content";
    static final String FIELD_EMBEDDING = "embedding";
    static final String FIELD_METADATA = "metadata";

    private final VectorStore vectorStore;
    private final EmbeddingModel embeddingModel;

    private final boolean hybridEnabled;
    private final int candidateK;
    private final int rrfK;
    private final String indexName;

    private final AtomicBoolean mappingLogged = new AtomicBoolean(false);

    public ElasticsearchChunkStore(VectorStore vectorStore, EmbeddingModel embeddingModel,
            @Value("${documind.retrieval.hybrid.enabled:false}") boolean hybridEnabled,
            @Value("${documind.retrieval.hybrid.candidate-k:20}") int candidateK,
            @Value("${documind.retrieval.hybrid.rrf-k:60}") int rrfK,
            @Value("${spring.ai.vectorstore.elasticsearch.index-name:documind-chunks}") String indexName) {
        this.vectorStore = vectorStore;
        this.embeddingModel = embeddingModel;
        this.hybridEnabled = hybridEnabled;
        this.candidateK = candidateK;
        this.rrfK = rrfK;
        this.indexName = indexName;
    }

    @Override
    public void save(List<Chunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        List<Document> documents = chunks.stream().map(ElasticsearchChunkStore::toDocument).toList();
        vectorStore.add(documents);
    }

    @Override
    public List<RetrievedChunk> search(String query, int topK) {
        return hybridEnabled ? hybridSearch(query, topK) : vectorSearch(query, topK);
    }

    /** Vector-only path (default): unchanged Spring AI kNN — the Slice-2 baseline. */
    private List<RetrievedChunk> vectorSearch(String query, int topK) {
        SearchRequest request = SearchRequest.builder().query(query).topK(topK).build();
        List<Document> results = vectorStore.similaritySearch(request);
        if (results == null) {
            return List.of();
        }
        return results.stream().map(ElasticsearchChunkStore::toRetrievedChunk).toList();
    }

    /**
     * Hybrid path: BM25 + kNN, each to depth {@code candidateK}, fused by RRF and cut to
     * {@code topK}. RRF score for a document is the sum over the lists it appears in of
     * {@code 1 / (rrfK + rank)} (1-based rank) — rank-based, so the incomparable BM25 and
     * cosine score scales never meet, and a document ranked well by both lists is boosted.
     */
    private List<RetrievedChunk> hybridSearch(String query, int topK) {
        ElasticsearchClient client = this.<ElasticsearchClient>nativeClient();
        logMappingOnce(client);

        List<Float> queryVector = toFloatList(embeddingModel.embed(query));
        List<Hit<Map>> lexical = runSearch(client, b -> b
                .index(indexName)
                .size(candidateK)
                .query(q -> q.match(m -> m.field(FIELD_CONTENT).query(query))));
        List<Hit<Map>> vector = runSearch(client, b -> b
                .index(indexName)
                .size(candidateK)
                .knn(k -> k.field(FIELD_EMBEDDING).queryVector(queryVector)
                        .k(candidateK).numCandidates(Math.max(candidateK * 4, 100))));

        Map<String, Double> fusedScore = new LinkedHashMap<>();
        Map<String, Hit<Map>> representative = new LinkedHashMap<>();
        accumulateRrf(lexical, fusedScore, representative);
        accumulateRrf(vector, fusedScore, representative);

        return fusedScore.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed()
                        .thenComparing(Map.Entry::getKey))
                .limit(topK)
                .map(e -> toRetrievedChunk(representative.get(e.getKey()).source(), e.getValue()))
                .toList();
    }

    /** Add one result list's RRF contribution, keyed by ES document id. */
    private void accumulateRrf(List<Hit<Map>> hits, Map<String, Double> fusedScore,
            Map<String, Hit<Map>> representative) {
        for (int rank = 0; rank < hits.size(); rank++) {
            Hit<Map> hit = hits.get(rank);
            fusedScore.merge(hit.id(), 1.0 / (rrfK + rank + 1), Double::sum);
            representative.putIfAbsent(hit.id(), hit);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static List<Hit<Map>> runSearch(ElasticsearchClient client,
            java.util.function.Function<co.elastic.clients.elasticsearch.core.SearchRequest.Builder,
                    co.elastic.clients.util.ObjectBuilder<co.elastic.clients.elasticsearch.core.SearchRequest>> fn) {
        try {
            SearchResponse<Map> response = client.search(fn::apply, Map.class);
            return (List<Hit<Map>>) (List<?>) response.hits().hits();
        }
        catch (Exception e) {
            throw new IllegalStateException("hybrid Elasticsearch query failed", e);
        }
    }

    private <T> T nativeClient() {
        return this.vectorStore.<T>getNativeClient().orElseThrow(() -> new IllegalStateException(
                "hybrid retrieval requires the Elasticsearch native client, but the VectorStore "
                        + "exposed none — is hybrid enabled against a non-Elasticsearch store?"));
    }

    private void logMappingOnce(ElasticsearchClient client) {
        if (mappingLogged.compareAndSet(false, true)) {
            try {
                var records = client.indices().getMapping(g -> g.index(indexName)).mappings();
                var properties = records.values().stream().findFirst()
                        .map(r -> r.mappings().properties()).orElse(Map.of());
                log.info("hybrid retrieval active (candidateK={}, rrfK={}); index '{}' fields={}, '{}' mapping={}",
                        candidateK, rrfK, indexName, properties.keySet(), FIELD_CONTENT, properties.get(FIELD_CONTENT));
            }
            catch (Exception e) {
                log.warn("hybrid retrieval active but could not read mapping for '{}': {}", indexName, e.toString());
            }
        }
    }

    private static Document toDocument(Chunk chunk) {
        Provenance p = chunk.provenance();
        // Stable id: re-ingesting the same document overwrites its chunks rather than
        // duplicating them. (documentId, chunkIndex) is unique within a document.
        String id = p.documentId() + "#" + p.chunkIndex();
        Map<String, Object> metadata = Map.of(
                META_DOCUMENT_ID, p.documentId(),
                META_FILENAME, p.filename(),
                META_PAGE_NUMBER, p.pageNumber(),
                META_CHUNK_INDEX, p.chunkIndex());
        return Document.builder().id(id).text(chunk.text()).metadata(metadata).build();
    }

    private static RetrievedChunk toRetrievedChunk(Document document) {
        Double score = document.getScore();
        return toRetrievedChunk(document.getText(), document.getMetadata(), score != null ? score : 0.0d);
    }

    /** Build from a native ES {@code _source} map (hybrid path), with the fused RRF score. */
    @SuppressWarnings("unchecked")
    private static RetrievedChunk toRetrievedChunk(Map source, double score) {
        String text = (String) source.get(FIELD_CONTENT);
        Map<String, Object> metadata = (Map<String, Object>) source.get(FIELD_METADATA);
        return toRetrievedChunk(text, metadata, score);
    }

    private static RetrievedChunk toRetrievedChunk(String text, Map<String, Object> metadata, double score) {
        Provenance provenance = new Provenance(
                (String) metadata.get(META_DOCUMENT_ID),
                (String) metadata.get(META_FILENAME),
                intValue(metadata.get(META_PAGE_NUMBER)),
                intValue(metadata.get(META_CHUNK_INDEX)));
        return new RetrievedChunk(new Chunk(text, provenance), score);
    }

    private static List<Float> toFloatList(float[] vector) {
        List<Float> list = new ArrayList<>(vector.length);
        for (float v : vector) {
            list.add(v);
        }
        return list;
    }

    // JSON numbers round-trip through ES as Integer or Long depending on magnitude;
    // normalize both to int. The provenance record then re-validates the ranges.
    private static int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        throw new IllegalStateException("expected a numeric metadata value, got: " + value);
    }
}
