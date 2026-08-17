package com.agent.controller;

import com.agent.dto.SessionDocumentsResponse;
import com.agent.service.DocumentService;
import com.agent.service.RagService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;
    private final RagService ragService;

    @GetMapping("/sessions/{sessionId}/documents")
    public SessionDocumentsResponse getSessionDocuments(@PathVariable String sessionId) {
        return documentService.listSessionDocuments(sessionId);
    }

    @PostMapping("/quick_parse")
    public Map<String, String> quickParse(
            @RequestParam("session_id") String sessionId,
            @RequestParam("file") MultipartFile file
    ) {
        ragService.saveSessionDocument(sessionId, file);
        return Map.of("status", "success", "message", "success");
    }
}
