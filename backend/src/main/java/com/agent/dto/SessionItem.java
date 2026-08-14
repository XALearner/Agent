package com.agent.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SessionItem {

    @JsonProperty("session_id")
    private String sessionId;

    @JsonProperty("session_name")
    private String sessionName;

    @JsonProperty("created_at")
    private String createdAt;

    @JsonProperty("updated_at")
    private String updatedAt;
}
