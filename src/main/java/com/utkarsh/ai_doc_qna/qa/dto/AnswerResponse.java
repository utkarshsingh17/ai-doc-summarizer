package com.utkarsh.ai_doc_qna.qa.dto;

import java.util.List;

/**
 * @param answerable      false when the corpus does not cover the question; {@code citations} is
 *                        then empty and {@code answer} explains what is missing
 * @param citations       the excerpts the model reported using, in the order it cited them
 * @param retrievedChunks how many chunks cleared the similarity threshold — useful for telling
 *                        "nothing matched" apart from "matched but did not answer the question"
 */
public record AnswerResponse(
        String question,
        String answer,
        boolean answerable,
        List<CitationResponse> citations,
        int retrievedChunks) {
}
