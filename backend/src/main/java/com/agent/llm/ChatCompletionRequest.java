package com.agent.llm;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ChatCompletionRequest {

    private String message;
    private String model;
    private String systemPrompt;
    private String sessionId;
    private boolean functionCallingEnabled;
}
