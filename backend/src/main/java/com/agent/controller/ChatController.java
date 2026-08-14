package com.agent.controller;

import com.agent.dto.ChatRequest;
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
    private final SessionService sessionService;

    @PostMapping(value = "/chat_on_docs", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public StreamingResponseBody chatOnDocs(
            @RequestParam("session_id") String sessionId,
            @Valid @RequestBody ChatRequest request
    ) {
        return outputStream -> {
            String answer = "后端框架已接通。这里是占位回复，后续可以替换为真实知识库问答或大模型调用。";
            for (String chunk : answer.split("(?<=。|，)")) {
                String payload = objectMapper.writeValueAsString(Map.of(
                        "content", chunk,
                        "thinking", false
                ));
                outputStream.write(("data: " + payload + "\n\n").getBytes(StandardCharsets.UTF_8));
                outputStream.flush();
            }
            outputStream.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            outputStream.flush();
            sessionService.saveChatResult(sessionId, request.getMessage(), answer);
        };
    }
}
