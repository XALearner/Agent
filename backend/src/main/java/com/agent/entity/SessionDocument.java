package com.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("session_documents")
public class SessionDocument {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String sessionId;
    private String documentName;
    private String documentType;
    private Long fileSize;
    private LocalDateTime uploadTime;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
