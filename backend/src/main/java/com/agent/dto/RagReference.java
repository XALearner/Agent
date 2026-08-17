package com.agent.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class RagReference {

    private String id;

    @JsonProperty("document_id")
    private String documentId;

    @JsonProperty("document_name")
    private String documentName;

    @JsonProperty("content_with_weight")
    private String contentWithWeight;

    private List<List<Integer>> positions;
}
