package com.agent.service;

import com.agent.dto.CreateSessionResponse;
import com.agent.dto.MessageItem;
import com.agent.dto.SessionItem;
import com.agent.dto.SessionsResponse;
import com.agent.entity.ChatMessage;
import com.agent.entity.ChatSession;
import com.agent.mapper.ChatMessageMapper;
import com.agent.mapper.ChatSessionMapper;
import com.agent.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SessionService {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final ChatSessionMapper chatSessionMapper;
    private final ChatMessageMapper chatMessageMapper;

    public SessionsResponse listSessions() {
        List<SessionItem> sessions = chatSessionMapper.selectList(new LambdaQueryWrapper<ChatSession>()
                        .orderByDesc(ChatSession::getUpdatedAt))
                .stream()
                .map(this::toSessionItem)
                .toList();
        return new SessionsResponse(sessions);
    }

    public List<MessageItem> listMessages(String sessionId) {
        return chatMessageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getSessionId, sessionId)
                        .orderByAsc(ChatMessage::getCreatedAt))
                .stream()
                .map(this::toMessageItem)
                .toList();
    }

    public CreateSessionResponse createSession() {
        LocalDateTime now = LocalDateTime.now();
        String sessionId = IdUtil.uuid();

        ChatSession session = new ChatSession();
        session.setSessionId(sessionId);
        session.setSessionName("新对话");
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        chatSessionMapper.insert(session);

        return new CreateSessionResponse(sessionId);
    }

    public void saveChatResult(String sessionId, String question, String answer) {
        saveChatResult(sessionId, question, answer, "[]");
    }

    public void saveChatResult(String sessionId, String question, String answer, String documents) {
        LocalDateTime now = LocalDateTime.now();

        ChatMessage message = new ChatMessage();
        message.setMessageId(IdUtil.uuid());
        message.setSessionId(sessionId);
        message.setUserQuestion(question);
        message.setModelAnswer(answer);
        message.setRecommendedQuestions("[]");
        message.setDocuments(documents);
        message.setCreatedAt(now);
        message.setUpdatedAt(now);
        chatMessageMapper.insert(message);

        ChatSession session = chatSessionMapper.selectOne(new LambdaQueryWrapper<ChatSession>()
                .eq(ChatSession::getSessionId, sessionId));
        if (session != null) {
            session.setSessionName(question.length() > 30 ? question.substring(0, 30) : question);
            session.setUpdatedAt(now);
            chatSessionMapper.updateById(session);
        }
    }

    private SessionItem toSessionItem(ChatSession session) {
        return SessionItem.builder()
                .sessionId(session.getSessionId())
                .sessionName(session.getSessionName())
                .createdAt(format(session.getCreatedAt()))
                .updatedAt(format(session.getUpdatedAt()))
                .build();
    }

    private MessageItem toMessageItem(ChatMessage message) {
        return MessageItem.builder()
                .createdAt(format(message.getCreatedAt()))
                .messageId(message.getMessageId())
                .sessionId(message.getSessionId())
                .userQuestion(message.getUserQuestion())
                .modelAnswer(message.getModelAnswer())
                .think(message.getThink())
                .documents(message.getDocuments())
                .recommendedQuestions(message.getRecommendedQuestions())
                .build();
    }

    private String format(LocalDateTime value) {
        return value == null ? null : value.format(FORMATTER);
    }
}
