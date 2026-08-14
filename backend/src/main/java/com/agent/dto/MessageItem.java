package com.agent.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MessageItem {

    @JsonProperty("created_at")
    private String createdAt;

    @JsonProperty("message_id")
    private String messageId;

    @JsonProperty("session_id")
    private String sessionId;

    @JsonProperty("user_question")
    private String userQuestion;

    @JsonProperty("model_answer")
    private String modelAnswer;

    private String think;
    private String documents;

    @JsonProperty("recommended_questions")
    private String recommendedQuestions;
}
