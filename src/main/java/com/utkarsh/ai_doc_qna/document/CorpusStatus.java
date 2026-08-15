package com.utkarsh.ai_doc_qna.document;

/**
 * A snapshot of what the corpus can currently answer from.
 *
 * <p>Used to tell three very different situations apart when a question matches nothing: an empty
 * corpus, a corpus still being ingested, and a corpus that genuinely does not cover the question.
 */
public record CorpusStatus(long total, long inProgress) {

    public boolean isEmpty() {
        return total == 0;
    }

    public boolean hasWorkInProgress() {
        return inProgress > 0;
    }
}
