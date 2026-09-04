package com.utkarsh.ai_doc_qna.document;

import com.utkarsh.ai_doc_qna.common.exception.DocumentNotFoundException;
import com.utkarsh.ai_doc_qna.common.exception.DuplicateDocumentException;
import com.utkarsh.ai_doc_qna.common.exception.UnsupportedFileTypeException;
import com.utkarsh.ai_doc_qna.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

    private static final UUID OWNER_ID = UUID.fromString("99999999-8888-7777-6666-555555555555");

    @Mock
    private SourceDocumentRepository repository;

    @Mock
    private VectorStore vectorStore;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private DocumentService documentService;

    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties(
                new AppProperties.Ingestion(800, 350, 1024 * 1024,
                        Set.of("application/pdf", "text/plain", "text/markdown")),
                new AppProperties.Qa(6, 0.45, 320),
                new AppProperties.Jwt("c2VjcmV0LWZvci10ZXN0cy1vbmx5LWF0LWxlYXN0LTMyLWJ5dGVzIQ==", 900000, 604800000, false),
                null);
        documentService = new DocumentService(repository, vectorStore, eventPublisher, properties);
    }

    @Test
    void upload_withNewFile_shouldStoreItAndPublishAnIngestionEvent() {
        when(repository.findByOwnerIdAndChecksum(any(), anyString())).thenReturn(Optional.empty());
        when(repository.save(any(SourceDocument.class), any())).thenAnswer(call -> call.getArgument(0));

        SourceDocument saved = documentService.upload(textFile("policy.txt", "remote work policy"), OWNER_ID);

        assertThat(saved.getFilename()).isEqualTo("policy.txt");
        assertThat(saved.getContentType()).isEqualTo("text/plain");
        assertThat(saved.getStatus()).isEqualTo(IngestionStatus.PENDING);
        assertThat(saved.getChunkCount()).isZero();
        assertThat(saved.getOwnerId()).isEqualTo(OWNER_ID);
        verify(eventPublisher).publishEvent(any(DocumentUploadedEvent.class));
    }

    @Test
    void upload_shouldPassTheUploadedBytesThroughToTheRepository() {
        when(repository.findByOwnerIdAndChecksum(any(), anyString())).thenReturn(Optional.empty());
        when(repository.save(any(SourceDocument.class), any())).thenAnswer(call -> call.getArgument(0));

        documentService.upload(textFile("policy.txt", "contents"), OWNER_ID);

        ArgumentCaptor<byte[]> content = ArgumentCaptor.forClass(byte[].class);
        verify(repository).save(any(SourceDocument.class), content.capture());
        assertThat(content.getValue()).isEqualTo("contents".getBytes());
    }

    @Test
    void upload_whenChecksumAlreadyExistsForTheSameOwner_shouldRejectBeforeSaving() {
        SourceDocument existing = SourceDocument.create("policy.txt", "text/plain", 10, "abc", OWNER_ID);
        when(repository.findByOwnerIdAndChecksum(any(), anyString())).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> documentService.upload(textFile("policy.txt", "remote work policy"), OWNER_ID))
                .isInstanceOf(DuplicateDocumentException.class);

        // Re-ingesting a duplicate would double its chunks and skew retrieval.
        verify(repository, never()).save(any(), any());
    }

    @Test
    void upload_withUnsupportedContentType_shouldBeRejected() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "report.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "content".getBytes());

        assertThatThrownBy(() -> documentService.upload(file, OWNER_ID))
                .isInstanceOf(UnsupportedFileTypeException.class);
    }

    @Test
    void upload_whenBrowserSendsOctetStreamForMarkdown_shouldResolveTypeFromTheExtension() {
        when(repository.findByOwnerIdAndChecksum(any(), anyString())).thenReturn(Optional.empty());
        when(repository.save(any(SourceDocument.class), any())).thenAnswer(call -> call.getArgument(0));

        MockMultipartFile file = new MockMultipartFile(
                "file", "notes.md", "application/octet-stream", "# Notes".getBytes());

        assertThat(documentService.upload(file, OWNER_ID).getContentType()).isEqualTo("text/markdown");
    }

    @Test
    void upload_withEmptyFile_shouldBeRejected() {
        MockMultipartFile file = new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0]);

        assertThatThrownBy(() -> documentService.upload(file, OWNER_ID))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void upload_withFileOverTheConfiguredLimit_shouldBeRejected() {
        byte[] tooBig = new byte[2 * 1024 * 1024]; // limit is 1 MB in these test properties
        MockMultipartFile file = new MockMultipartFile("file", "big.txt", "text/plain", tooBig);

        assertThatThrownBy(() -> documentService.upload(file, OWNER_ID))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void upload_shouldStripPathTraversalFromTheFilename() {
        when(repository.findByOwnerIdAndChecksum(any(), anyString())).thenReturn(Optional.empty());
        when(repository.save(any(SourceDocument.class), any())).thenAnswer(call -> call.getArgument(0));

        MockMultipartFile file = new MockMultipartFile(
                "file", "../../etc/passwd.txt", "text/plain", "content".getBytes());

        assertThat(documentService.upload(file, OWNER_ID).getFilename()).isEqualTo("passwd.txt");
    }

    @Test
    void delete_shouldRemoveChunksAndTheDocumentPoint() {
        SourceDocument document = SourceDocument.create("policy.txt", "text/plain", 10, "abc", OWNER_ID);
        UUID id = UUID.randomUUID();
        when(repository.findByIdAndOwnerId(id, OWNER_ID)).thenReturn(Optional.of(document));

        documentService.delete(id, OWNER_ID);

        ArgumentCaptor<String> filter = ArgumentCaptor.forClass(String.class);
        verify(vectorStore).delete(filter.capture());
        assertThat(filter.getValue()).startsWith("document_id == '");
        verify(repository).delete(document);
    }

    @Test
    void get_whenIdIsUnknown_shouldThrowNotFound() {
        UUID id = UUID.randomUUID();
        when(repository.findByIdAndOwnerId(id, OWNER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> documentService.get(id, OWNER_ID)).isInstanceOf(DocumentNotFoundException.class);
    }

    @Test
    void get_whenDocumentBelongsToAnotherOwner_shouldThrowNotFound() {
        UUID id = UUID.randomUUID();
        // The repository query itself is owner-scoped, so another owner's document simply
        // never matches — same as it not existing at all.
        when(repository.findByIdAndOwnerId(id, OWNER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> documentService.get(id, OWNER_ID)).isInstanceOf(DocumentNotFoundException.class);
    }

    private static MockMultipartFile textFile(String name, String content) {
        return new MockMultipartFile("file", name, "text/plain", content.getBytes());
    }
}
