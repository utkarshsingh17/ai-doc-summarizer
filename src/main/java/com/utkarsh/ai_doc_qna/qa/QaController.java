package com.utkarsh.ai_doc_qna.qa;

import com.utkarsh.ai_doc_qna.common.ApiResponse;
import com.utkarsh.ai_doc_qna.qa.dto.AnswerResponse;
import com.utkarsh.ai_doc_qna.qa.dto.AskQuestionRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/questions")
public class QaController {

    private final QaService qaService;

    public QaController(QaService qaService) {
        this.qaService = qaService;
    }


    /**
     * POST rather than GET: questions can be long enough to strain a query string, and the
     * request body keeps them out of access logs.
     */
    @PostMapping
    public ApiResponse<AnswerResponse> ask(@Valid @RequestBody AskQuestionRequest request) {
        return ApiResponse.ok(qaService.ask(request.question()));
    }
}
