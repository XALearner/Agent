package com.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("repository_files")
public class RepositoryFile {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String fileId;
    private String fileName;
    private String storagePath;
    private Long fileSize;
    private String userId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
