package com.utkarsh.ai_doc_qna.qa;

import com.utkarsh.ai_doc_qna.auth.UserPrincipal;
import com.utkarsh.ai_doc_qna.common.ApiResponse;
import com.utkarsh.ai_doc_qna.qa.dto.AnswerResponse;
import com.utkarsh.ai_doc_qna.qa.dto.AskQuestionRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class QaController {

    private final QaService qaService;

    public QaController(QaService qaService) {
        this.qaService = qaService;
    }


    /**
     * Answers from the caller's whole corpus, or from just {@code documentIds} when given. POST
     * rather than GET: questions can be long enough to strain a query string, and the request
     * body keeps them out of access logs.
     */
    @PostMapping("/questions")
    public ApiResponse<AnswerResponse> ask(@Valid @RequestBody AskQuestionRequest request,
                                           @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.ok(qaService.ask(request.question(), principal.getId(), request.documentIds()));
    }

    /**
     * Answers from one document only. A document owned by someone else looks exactly like one
     * that does not exist — see {@link QaService#askAboutDocument}.
     */
    @PostMapping("/documents/{documentId}/questions")
    public ApiResponse<AnswerResponse> askAboutDocument(@PathVariable UUID documentId,
                                                         @Valid @RequestBody AskQuestionRequest request,
                                                         @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.ok(qaService.askAboutDocument(request.question(), documentId, principal.getId()));
    }
}
