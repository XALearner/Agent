package com.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("session_document_chunks")
public class SessionDocumentChunk {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long documentId;
    private String sessionId;
    private Integer chunkIndex;
    private Integer startOffset;
    private Integer endOffset;
    private String content;
    private String embedding;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
