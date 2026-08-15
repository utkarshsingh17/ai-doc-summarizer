package com.utkarsh.ai_doc_qna.document;

import com.utkarsh.ai_doc_qna.common.exception.DocumentNotFoundException;
import com.utkarsh.ai_doc_qna.common.exception.DuplicateDocumentException;
import com.utkarsh.ai_doc_qna.common.exception.UnsupportedFileTypeException;
import com.utkarsh.ai_doc_qna.config.AppProperties;
import com.utkarsh.ai_doc_qna.storage.DocumentStorage;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

    @Mock
    private SourceDocumentRepository repository;

    @Mock
    private DocumentStorage storage;

    @Mock
    private VectorStore vectorStore;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private DocumentService documentService;

    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties(
                new AppProperties.Storage("http://localhost:9000", "us-east-1", "test-bucket",
                        "key", "secret", true),
                new AppProperties.Ingestion(800, 350, 1024 * 1024,
                        Set.of("application/pdf", "text/plain", "text/markdown")),
                new AppProperties.Qa(6, 0.45, 320));
        documentService = new DocumentService(repository, storage, vectorStore, eventPublisher, properties);
    }

    @Test
    void upload_withNewFile_shouldStoreItAndPublishAnIngestionEvent() {
        when(repository.findByChecksum(anyString())).thenReturn(Optional.empty());
        when(repository.save(any(SourceDocument.class))).thenAnswer(call -> call.getArgument(0));

        SourceDocument saved = documentService.upload(textFile("policy.txt", "remote work policy"));

        assertThat(saved.getFilename()).isEqualTo("policy.txt");
        assertThat(saved.getContentType()).isEqualTo("text/plain");
        assertThat(saved.getStatus()).isEqualTo(IngestionStatus.PENDING);
        assertThat(saved.getChunkCount()).isZero();
        verify(storage).store(anyString(), any(), anyLong(), anyString());
        verify(eventPublisher).publishEvent(any(DocumentUploadedEvent.class));
    }

    @Test
    void upload_shouldNamespaceTheStorageKeySoIdenticalFilenamesDoNotCollide() {
        when(repository.findByChecksum(anyString())).thenReturn(Optional.empty());
        when(repository.save(any(SourceDocument.class))).thenAnswer(call -> call.getArgument(0));

        documentService.upload(textFile("policy.txt", "contents"));

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(storage).store(key.capture(), any(), anyLong(), anyString());
        assertThat(key.getValue()).startsWith("documents/").endsWith("/policy.txt");
    }

    @Test
    void upload_whenChecksumAlreadyExists_shouldRejectBeforeTouchingStorage() {
        SourceDocument existing = SourceDocument.create("policy.txt", "text/plain", 10, "abc", "key");
        when(repository.findByChecksum(anyString())).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> documentService.upload(textFile("policy.txt", "remote work policy")))
                .isInstanceOf(DuplicateDocumentException.class);

        // Re-ingesting a duplicate would double its chunks and skew retrieval.
        verifyNoInteractions(storage);
        verify(repository, never()).save(any());
    }

    @Test
    void upload_withUnsupportedContentType_shouldBeRejected() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "report.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "content".getBytes());

        assertThatThrownBy(() -> documentService.upload(file))
                .isInstanceOf(UnsupportedFileTypeException.class);
        verifyNoInteractions(storage);
    }

    @Test
    void upload_whenBrowserSendsOctetStreamForMarkdown_shouldResolveTypeFromTheExtension() {
        when(repository.findByChecksum(anyString())).thenReturn(Optional.empty());
        when(repository.save(any(SourceDocument.class))).thenAnswer(call -> call.getArgument(0));

        MockMultipartFile file = new MockMultipartFile(
                "file", "notes.md", "application/octet-stream", "# Notes".getBytes());

        assertThat(documentService.upload(file).getContentType()).isEqualTo("text/markdown");
    }

    @Test
    void upload_withEmptyFile_shouldBeRejected() {
        MockMultipartFile file = new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0]);

        assertThatThrownBy(() -> documentService.upload(file))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void upload_withFileOverTheConfiguredLimit_shouldBeRejected() {
        byte[] tooBig = new byte[2 * 1024 * 1024]; // limit is 1 MB in these test properties
        MockMultipartFile file = new MockMultipartFile("file", "big.txt", "text/plain", tooBig);

        assertThatThrownBy(() -> documentService.upload(file))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(storage);
    }

    @Test
    void upload_shouldStripPathTraversalFromTheFilename() {
        when(repository.findByChecksum(anyString())).thenReturn(Optional.empty());
        when(repository.save(any(SourceDocument.class))).thenAnswer(call -> call.getArgument(0));

        MockMultipartFile file = new MockMultipartFile(
                "file", "../../etc/passwd.txt", "text/plain", "content".getBytes());

        assertThat(documentService.upload(file).getFilename()).isEqualTo("passwd.txt");
    }

    @Test
    void delete_shouldRemoveChunksObjectAndRow() {
        SourceDocument document = SourceDocument.create("policy.txt", "text/plain", 10, "abc", "documents/x/policy.txt");
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(document));

        documentService.delete(id);

        ArgumentCaptor<String> filter = ArgumentCaptor.forClass(String.class);
        verify(vectorStore).delete(filter.capture());
        assertThat(filter.getValue()).startsWith("document_id == '");
        verify(storage).delete("documents/x/policy.txt");
        verify(repository).delete(document);
    }

    @Test
    void get_whenIdIsUnknown_shouldThrowNotFound() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> documentService.get(id)).isInstanceOf(DocumentNotFoundException.class);
    }

    private static MockMultipartFile textFile(String name, String content) {
        return new MockMultipartFile("file", name, "text/plain", content.getBytes());
    }
}
