package com.agent.controller;

import com.agent.dto.ChatRequest;
import com.agent.llm.ChatCompletionRequest;
import com.agent.service.ChatModelService;
import com.agent.service.SessionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class ChatController {

    private final ObjectMapper objectMapper;
    private final ChatModelService chatModelService;
    private final SessionService sessionService;

    @PostMapping(value = "/chat_on_docs", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public StreamingResponseBody chatOnDocs(
            @RequestParam("session_id") String sessionId,
            @Valid @RequestBody ChatRequest request
    ) {
        return outputStream -> {
            ChatCompletionRequest modelRequest = ChatCompletionRequest.builder()
                    .message(request.getMessage())
                    .model(request.getModel())
                    .systemPrompt(request.getSystemPrompt())
                    .build();

            String answer = chatModelService.streamChat(request.getProvider(), modelRequest, (content, thinking) -> {
                String payload = objectMapper.writeValueAsString(Map.of(
                        "content", content,
                        "thinking", thinking
                ));
                outputStream.write(("data: " + payload + "\n\n").getBytes(StandardCharsets.UTF_8));
                outputStream.flush();
            });
            outputStream.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            outputStream.flush();
            sessionService.saveChatResult(sessionId, request.getMessage(), answer);
        };
    }
}
