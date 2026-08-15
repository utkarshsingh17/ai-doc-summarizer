package com.utkarsh.ai_doc_qna.document;

import com.utkarsh.ai_doc_qna.common.exception.DocumentNotFoundException;
import com.utkarsh.ai_doc_qna.common.exception.DuplicateDocumentException;
import com.utkarsh.ai_doc_qna.common.exception.StorageException;
import com.utkarsh.ai_doc_qna.common.exception.UnsupportedFileTypeException;
import com.utkarsh.ai_doc_qna.config.AppProperties;
import com.utkarsh.ai_doc_qna.storage.DocumentStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.DigestInputStream;
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
    private final DocumentStorage storage;
    private final VectorStore vectorStore;
    private final ApplicationEventPublisher eventPublisher;
    private final AppProperties properties;

    public DocumentService(SourceDocumentRepository repository,
                           DocumentStorage storage,
                           VectorStore vectorStore,
                           ApplicationEventPublisher eventPublisher,
                           AppProperties properties) {
        this.repository = repository;
        this.storage = storage;
        this.vectorStore = vectorStore;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
    }


    /**
     * Stores the file and records it as {@code PENDING}. Parsing and embedding happen after
     * commit on a background thread, so a large PDF does not hold the HTTP request open.
     */
    @Transactional
    public SourceDocument upload(MultipartFile file) {
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

        // Hashing the content first means a re-upload is rejected before it costs an object write
        // or, worse, a second copy of every chunk in the vector store.
        String checksum = checksum(file);
        repository.findByChecksum(checksum).ifPresent(existing -> {
            throw new DuplicateDocumentException(existing.getId(), existing.getFilename());
        });

        String storageKey = "documents/%s/%s".formatted(UUID.randomUUID(), filename);
        try (InputStream content = file.getInputStream()) {
            storage.store(storageKey, content, file.getSize(), contentType);
        } catch (IOException ex) {
            throw new StorageException("Failed to read uploaded file " + filename, ex);
        }
        // If the transaction rolls back (for example a concurrent upload wins the checksum
        // constraint), the object we just wrote would otherwise be orphaned.
        deleteObjectOnRollback(storageKey);

        SourceDocument document = repository.save(
                SourceDocument.create(filename, contentType, file.getSize(), checksum, storageKey));
        eventPublisher.publishEvent(new DocumentUploadedEvent(document.getId()));

        log.info("Accepted upload {} ({} bytes) as document {}", filename, file.getSize(), document.getId());
        return document;
    }

    @Transactional(readOnly = true)
    public Page<SourceDocument> list(Pageable pageable) {
        return repository.findAll(pageable);
    }

    @Transactional(readOnly = true)
    public SourceDocument get(UUID id) {
        return repository.findById(id).orElseThrow(() -> new DocumentNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public CorpusStatus corpusStatus() {
        long inProgress = repository.countByStatus(IngestionStatus.PENDING)
                + repository.countByStatus(IngestionStatus.PROCESSING);
        return new CorpusStatus(repository.count(), inProgress);
    }

    /**
     * Removes the document, its chunks and its stored object. Chunks go first: a document whose
     * row is gone but whose chunks linger would keep producing citations that resolve to nothing.
     */
    @Transactional
    public void delete(UUID id) {
        SourceDocument document = repository.findById(id).orElseThrow(() -> new DocumentNotFoundException(id));

        vectorStore.delete("%s == '%s'".formatted(ChunkMetadata.DOCUMENT_ID, document.getId()));
        storage.delete(document.getStorageKey());
        repository.delete(document);

        log.info("Deleted document {} ({})", id, document.getFilename());
    }

    private void deleteObjectOnRollback(String storageKey) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    try {
                        storage.delete(storageKey);
                    } catch (RuntimeException ex) {
                        log.warn("Could not clean up orphaned object {}", storageKey, ex);
                    }
                }
            }
        });
    }

    private String checksum(MultipartFile file) {
        try (InputStream input = file.getInputStream();
             DigestInputStream digestStream = new DigestInputStream(input, MessageDigest.getInstance("SHA-256"))) {
            digestStream.transferTo(OutputStream.nullOutputStream());
            return HexFormat.of().formatHex(digestStream.getMessageDigest().digest());
        } catch (IOException ex) {
            throw new StorageException("Failed to read uploaded file for checksumming", ex);
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
