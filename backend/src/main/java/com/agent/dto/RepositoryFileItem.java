package com.agent.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RepositoryFileItem {

    @JsonProperty("file_name")
    private String fileName;

    @JsonProperty("user_id")
    private String userId;

    @JsonProperty("created_at")
    private String createdAt;

    @JsonProperty("updated_at")
    private String updatedAt;
}
