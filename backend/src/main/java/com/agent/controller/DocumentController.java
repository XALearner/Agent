package com.agent.controller;

import com.agent.dto.SessionDocumentsResponse;
import com.agent.service.DocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;

    @GetMapping("/sessions/{sessionId}/documents")
    public SessionDocumentsResponse getSessionDocuments(@PathVariable String sessionId) {
        return documentService.listSessionDocuments(sessionId);
    }
}
