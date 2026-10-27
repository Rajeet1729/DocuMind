package io.github.agentic.documind.adapter.out.elasticsearch;

import io.github.agentic.documind.application.port.out.ChunkStore;
import io.github.agentic.documind.domain.Chunk;
import io.github.agentic.documind.domain.Provenance;
import io.github.agentic.documind.domain.RetrievedChunk;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * Elasticsearch-backed {@link ChunkStore}, built on the Spring AI {@link VectorStore}.
 *
 * <p>This adapter is the single home of the {@link Provenance} &harr; vector-store
 * metadata mapping. The same four keys are written in {@link #save} and read back in
 * {@link #search}, so a citation's provenance round-trips exactly. Embedding is the
 * vector store's concern (it embeds on write and on query); this adapter never touches
 * an embedding model.
 */
@Repository
public class ElasticsearchChunkStore implements ChunkStore {

    // The one definition of the Chunk-provenance <-> ES-metadata mapping. Applied
    // identically on write (save) and read (search).
    static final String META_DOCUMENT_ID = "documentId";
    static final String META_FILENAME = "filename";
    static final String META_PAGE_NUMBER = "pageNumber";
    static final String META_CHUNK_INDEX = "chunkIndex";

    private final VectorStore vectorStore;

    public ElasticsearchChunkStore(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
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
        SearchRequest request = SearchRequest.builder().query(query).topK(topK).build();
        List<Document> results = vectorStore.similaritySearch(request);
        if (results == null) {
            return List.of();
        }
        return results.stream().map(ElasticsearchChunkStore::toRetrievedChunk).toList();
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
        Map<String, Object> metadata = document.getMetadata();
        Provenance provenance = new Provenance(
                (String) metadata.get(META_DOCUMENT_ID),
                (String) metadata.get(META_FILENAME),
                intValue(metadata.get(META_PAGE_NUMBER)),
                intValue(metadata.get(META_CHUNK_INDEX)));
        Chunk chunk = new Chunk(document.getText(), provenance);
        Double score = document.getScore();
        return new RetrievedChunk(chunk, score != null ? score : 0.0d);
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
