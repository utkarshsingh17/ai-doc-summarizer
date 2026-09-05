package com.utkarsh.ai_doc_qna.document;

import com.utkarsh.ai_doc_qna.config.AppProperties;
import com.utkarsh.ai_doc_qna.config.AsyncConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Turns a stored file into embedded, searchable chunks.
 *
 * <p>Runs on a background thread so the uploader gets an immediate response while parsing and
 * embedding proceed. Status transitions go through {@link IngestionStatusWriter}, so progress and
 * failures are visible to {@code GET /documents/{id}} while the work is still running.
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final SourceDocumentRepository repository;
    private final IngestionStatusWriter statusWriter;
    private final VectorStore vectorStore;
    private final AppProperties properties;

    public IngestionService(SourceDocumentRepository repository,
                            IngestionStatusWriter statusWriter,
                            VectorStore vectorStore,
                            AppProperties properties) {
        this.repository = repository;
        this.statusWriter = statusWriter;
        this.vectorStore = vectorStore;
        this.properties = properties;
    }


    @Async(AsyncConfig.INGESTION_EXECUTOR)
    @EventListener
    public void onDocumentUploaded(DocumentUploadedEvent event) {
        ingest(event.documentId());
    }

    public void ingest(UUID documentId) {
        Optional<SourceDocument> found = repository.findById(documentId);
        if (found.isEmpty()) {
            log.warn("Document {} disappeared before ingestion started", documentId);
            return;
        }
        SourceDocument document = found.get();

        statusWriter.apply(documentId, SourceDocument::markProcessing);
        long startedAt = System.currentTimeMillis();

        try {
            List<Document> chunks = split(document);
            if (chunks.isEmpty()) {
                // A scanned, image-only PDF extracts no text. Failing loudly beats a "completed"
                // document that silently answers nothing.
                statusWriter.apply(documentId, doc -> doc.markFailed(
                        "No extractable text found. Scanned or image-only files require OCR."));
                return;
            }

            vectorStore.add(chunks);
            statusWriter.apply(documentId, doc -> doc.markCompleted(chunks.size()));
            log.info("Ingested document {} into {} chunks in {} ms",
                    documentId, chunks.size(), System.currentTimeMillis() - startedAt);
            clearStoredContent(documentId);
        } catch (Exception | StackOverflowError ex) {
            // StackOverflowError is caught alongside Exception, not left to the async executor's
            // default handler: the tokenizer's regex-based pretokenizer recurses per match, and a
            // large enough page of text can blow the stack. Without this, the document would be
            // stuck at PROCESSING forever instead of visibly FAILED.
            log.error("Ingestion failed for document {}", documentId, ex);
            statusWriter.apply(documentId, doc -> doc.markFailed(describe(ex)));
        }
    }

    private List<Document> split(SourceDocument document) {
        AppProperties.Ingestion config = properties.ingestion();
        List<Document> parsed = parse(document);

        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(config.chunkSizeTokens())
                .withMinChunkSizeChars(config.minChunkSizeChars())
                .build();
        List<Document> chunks = splitter.apply(parsed);

        // The splitter copies each parent's metadata onto its chunks. Tika returns one Document
        // per file with no page_number, so citations from here on carry a filename but no page.
        // We add the ownership and ordering keys here.
        List<Document> stamped = new ArrayList<>(chunks.size());
        for (int index = 0; index < chunks.size(); index++) {
            Document chunk = chunks.get(index);
            stamped.add(chunk.mutate()
                    // Document's default id is a hash of its content, so two chunks with identical
                    // text (repeated headers, boilerplate) would collide and the store's
                    // upsert-by-id behavior would silently merge them. A deterministic id derived
                    // from document + position keeps chunks distinct and re-ingestion idempotent.
                    .id(chunkId(document.getId(), index))
                    .metadata(ChunkMetadata.DOCUMENT_ID, document.getId().toString())
                    .metadata(ChunkMetadata.OWNER_ID, document.getOwnerId().toString())
                    .metadata(ChunkMetadata.FILENAME, document.getFilename())
                    .metadata(ChunkMetadata.CHUNK_INDEX, index)
                    .build());
        }
        return stamped;
    }

    private List<Document> parse(SourceDocument document) {
        try {
            byte[] content = repository.loadContent(document.getId());
            Resource resource = new ByteArrayResource(content);
            return new TikaDocumentReader(resource).get();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to parse " + document.getFilename(), ex);
        }
    }

    /**
     * The document is already marked {@code COMPLETED} and answerable by the time this runs —
     * failing to free the now-unneeded file bytes is unfortunate but must never undo that, so
     * this never lets an exception propagate back into the outer catch block.
     */
    private void clearStoredContent(UUID documentId) {
        try {
            repository.clearContent(documentId);
        } catch (Exception ex) {
            log.warn("Could not clear stored content for document {}", documentId, ex);
        }
    }

    private static String chunkId(UUID documentId, int index) {
        return UUID.nameUUIDFromBytes(
                (documentId + ":" + index).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String describe(Throwable ex) {
        Throwable root = ex;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return "%s: %s".formatted(root.getClass().getSimpleName(), root.getMessage());
    }
}
