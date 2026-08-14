package com.agent.service;

import com.agent.dto.SessionDocumentItem;
import com.agent.dto.SessionDocumentsResponse;
import com.agent.entity.SessionDocument;
import com.agent.mapper.SessionDocumentMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DocumentService {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final SessionDocumentMapper sessionDocumentMapper;

    public SessionDocumentsResponse listSessionDocuments(String sessionId) {
        List<SessionDocumentItem> documents = sessionDocumentMapper.selectList(new LambdaQueryWrapper<SessionDocument>()
                        .eq(SessionDocument::getSessionId, sessionId)
                        .orderByDesc(SessionDocument::getUpdatedAt))
                .stream()
                .map(this::toItem)
                .toList();
        return new SessionDocumentsResponse(documents, !documents.isEmpty());
    }

    private SessionDocumentItem toItem(SessionDocument document) {
        return SessionDocumentItem.builder()
                .id(document.getId())
                .sessionId(document.getSessionId())
                .documentName(document.getDocumentName())
                .documentType(document.getDocumentType())
                .fileSize(document.getFileSize())
                .uploadTime(format(document.getUploadTime()))
                .createdAt(format(document.getCreatedAt()))
                .updatedAt(format(document.getUpdatedAt()))
                .build();
    }

    private String format(LocalDateTime value) {
        return value == null ? null : value.format(FORMATTER);
    }
}
