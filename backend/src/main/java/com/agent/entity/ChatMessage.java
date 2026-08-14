package com.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("chat_messages")
public class ChatMessage {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String messageId;
    private String sessionId;
    private String userQuestion;
    private String modelAnswer;
    private String think;
    private String documents;
    private String recommendedQuestions;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
