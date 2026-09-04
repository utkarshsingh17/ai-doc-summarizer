package com.utkarsh.ai_doc_qna.document;

import com.utkarsh.ai_doc_qna.common.exception.DocumentNotFoundException;
import com.utkarsh.ai_doc_qna.common.exception.DuplicateDocumentException;
import com.utkarsh.ai_doc_qna.common.exception.StorageException;
import com.utkarsh.ai_doc_qna.common.exception.UnsupportedFileTypeException;
import com.utkarsh.ai_doc_qna.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private static final Map<String, String> EXTENSION_CONTENT_TYPES = Map.of(
            "pdf", "application/pdf",
            "txt", "text/plain",
            "md", "text/markdown",
            "markdown", "text/markdown");

    private final SourceDocumentRepository repository;
    private final VectorStore vectorStore;
    private final ApplicationEventPublisher eventPublisher;
    private final AppProperties properties;

    public DocumentService(SourceDocumentRepository repository,
                           VectorStore vectorStore,
                           ApplicationEventPublisher eventPublisher,
                           AppProperties properties) {
        this.repository = repository;
        this.vectorStore = vectorStore;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
    }


    /**
     * Stores the file (metadata and raw bytes together, as one Qdrant point) and publishes an
     * ingestion event. Parsing and embedding happen after this returns, on a background thread,
     * so a large upload does not hold the HTTP request open.
     */
    public SourceDocument upload(MultipartFile file, UUID ownerId) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file is empty");
        }

        AppProperties.Ingestion ingestion = properties.ingestion();
        if (file.getSize() > ingestion.maxFileSizeBytes()) {
            throw new IllegalArgumentException(
                    "File exceeds the maximum size of %d bytes".formatted(ingestion.maxFileSizeBytes()));
        }

        String filename = sanitizeFilename(file.getOriginalFilename());
        String contentType = resolveContentType(file.getContentType(), filename);
        if (!ingestion.allowedContentTypes().contains(contentType)) {
            throw new UnsupportedFileTypeException(contentType, ingestion.allowedContentTypes());
        }

        byte[] content = readAllBytes(file);

        // Hashing the content first means a re-upload is rejected before it costs a write to
        // Qdrant or, worse, a second copy of every chunk in the vector store. Scoped to the
        // owner: the same content uploaded by a different user is not a duplicate. Not race-free:
        // two concurrent uploads of identical content can both pass this check before either
        // writes — Qdrant has no unique constraint to fall back on the way the old Postgres
        // schema did, an accepted trade-off of moving document storage onto a vector database.
        String checksum = checksum(content);
        repository.findByOwnerIdAndChecksum(ownerId, checksum).ifPresent(existing -> {
            throw new DuplicateDocumentException(existing.getId(), existing.getFilename());
        });

        SourceDocument document = repository.save(
                SourceDocument.create(filename, contentType, file.getSize(), checksum, ownerId), content);
        eventPublisher.publishEvent(new DocumentUploadedEvent(document.getId()));

        log.info("Accepted upload {} ({} bytes) as document {}", filename, file.getSize(), document.getId());
        return document;
    }

    public Page<SourceDocument> list(Pageable pageable, UUID ownerId) {
        return repository.findAllByOwnerId(ownerId, pageable);
    }

    /**
     * Scoped to the owner rather than a plain {@code findById} + separate ownership check: a
     * document that exists but belongs to someone else looks exactly like one that does not
     * exist, so it cannot be used to enumerate other users' document ids.
     */
    public SourceDocument get(UUID id, UUID ownerId) {
        return repository.findByIdAndOwnerId(id, ownerId).orElseThrow(() -> new DocumentNotFoundException(id));
    }

    public CorpusStatus corpusStatus(UUID ownerId) {
        long inProgress = repository.countByOwnerIdAndStatus(ownerId, IngestionStatus.PENDING)
                + repository.countByOwnerIdAndStatus(ownerId, IngestionStatus.PROCESSING);
        return new CorpusStatus(repository.countByOwnerId(ownerId), inProgress);
    }

    /**
     * Removes the document and its chunks. The document's own point carries its content
     * alongside its metadata, so deleting it takes both at once; chunks go through a separate
     * filtered delete against the {@code vector_store} collection, first, so a citation never
     * resolves to a document that is already gone.
     */
    public void delete(UUID id, UUID ownerId) {
        SourceDocument document = repository.findByIdAndOwnerId(id, ownerId)
                .orElseThrow(() -> new DocumentNotFoundException(id));

        vectorStore.delete("%s == '%s'".formatted(ChunkMetadata.DOCUMENT_ID, document.getId()));
        repository.delete(document);

        log.info("Deleted document {} ({})", id, document.getFilename());
    }

    private static byte[] readAllBytes(MultipartFile file) {
        try (InputStream content = file.getInputStream()) {
            return content.readAllBytes();
        } catch (IOException ex) {
            throw new StorageException("Failed to read uploaded file " + file.getOriginalFilename(), ex);
        }
    }

    private static String checksum(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by every JRE", ex);
        }
    }

    private static String sanitizeFilename(String originalFilename) {
        String cleaned = StringUtils.getFilename(StringUtils.cleanPath(
                originalFilename == null ? "" : originalFilename));
        if (!StringUtils.hasText(cleaned)) {
            throw new IllegalArgumentException("Uploaded file must have a filename");
        }
        return cleaned;
    }

    /**
     * Browsers frequently send {@code application/octet-stream} for .md and .txt, so the
     * extension is the more reliable signal when the declared type is generic.
     */
    private static String resolveContentType(String declaredContentType, String filename) {
        String extension = StringUtils.getFilenameExtension(filename);
        String byExtension = extension == null
                ? null
                : EXTENSION_CONTENT_TYPES.get(extension.toLowerCase());
        if (byExtension != null) {
            return byExtension;
        }
        if (StringUtils.hasText(declaredContentType)) {
            // Strip any charset parameter, e.g. "text/plain;charset=UTF-8".
            return declaredContentType.split(";", 2)[0].trim().toLowerCase();
        }
        throw new UnsupportedFileTypeException("unknown", EXTENSION_CONTENT_TYPES.values());
    }
}
