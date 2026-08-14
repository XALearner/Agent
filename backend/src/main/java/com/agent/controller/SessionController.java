package com.agent.controller;

import com.agent.dto.CreateSessionResponse;
import com.agent.dto.MessageItem;
import com.agent.dto.SessionsResponse;
import com.agent.service.SessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class SessionController {

    private final SessionService sessionService;

    @GetMapping("/get_sessions")
    public SessionsResponse getSessions() {
        return sessionService.listSessions();
    }

    @GetMapping("/get_messages")
    public List<MessageItem> getMessages(@RequestParam("session_id") String sessionId) {
        return sessionService.listMessages(sessionId);
    }

    @PostMapping("/create_session")
    public Map<String, String> createSession() {
        CreateSessionResponse response = sessionService.createSession();
        return Map.of(
                "status", "success",
                "message", "success",
                "session_id", response.getSessionId()
        );
    }
}
