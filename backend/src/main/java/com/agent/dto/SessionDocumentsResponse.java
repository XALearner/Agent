package com.agent.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class SessionDocumentsResponse {

    private List<SessionDocumentItem> documents;

    @JsonProperty("has_documents")
    private boolean hasDocuments;
}
