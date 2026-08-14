package com.agent.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SessionDocumentItem {

    private Long id;

    @JsonProperty("session_id")
    private String sessionId;

    @JsonProperty("document_name")
    private String documentName;

    @JsonProperty("document_type")
    private String documentType;

    @JsonProperty("file_size")
    private Long fileSize;

    @JsonProperty("upload_time")
    private String uploadTime;

    @JsonProperty("created_at")
    private String createdAt;

    @JsonProperty("updated_at")
    private String updatedAt;
}
