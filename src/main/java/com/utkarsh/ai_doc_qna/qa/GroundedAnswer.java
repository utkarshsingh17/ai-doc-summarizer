package com.utkarsh.ai_doc_qna.qa;

import java.util.List;

/**
 * Structured output from the model.
 *
 * <p>{@code usedSources} holds the 1-based excerpt numbers from the prompt. Asking the model to
 * name its sources — rather than inferring them from what was retrieved — is what makes the
 * citations honest: a chunk that was retrieved but not actually used is not cited.
 */
public record GroundedAnswer(
        String answer,
        boolean answerable,
        List<Integer> usedSources) {

    public List<Integer> usedSources() {
        return usedSources == null ? List.of() : usedSources;
    }
}
