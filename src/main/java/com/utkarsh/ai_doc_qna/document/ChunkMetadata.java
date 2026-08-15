package com.utkarsh.ai_doc_qna.document;

/**
 * Metadata keys stamped onto every chunk stored in the vector store.
 *
 * <p>These are the only link between a chunk and the document it came from — there is no chunk
 * table — so they carry both deletion (filter on {@link #DOCUMENT_ID}) and citation display.
 */
public final class ChunkMetadata {

    /** UUID of the owning {@code source_documents} row, as a string. */
    public static final String DOCUMENT_ID = "document_id";

    /** Original filename, shown in citations. */
    public static final String FILENAME = "filename";

    /** 1-based page number; set by {@code PagePdfDocumentReader}, absent for plain text. */
    public static final String PAGE_NUMBER = "page_number";

    /** 0-based position of the chunk within the document, for stable ordering of citations. */
    public static final String CHUNK_INDEX = "chunk_index";

    private ChunkMetadata() {
    }
}
