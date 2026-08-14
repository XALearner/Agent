package com.agent.service;

import com.agent.dto.FileUploadResponse;
import com.agent.dto.RepositoryFileItem;
import com.agent.entity.RepositoryFile;
import com.agent.exception.BizException;
import com.agent.mapper.RepositoryFileMapper;
import com.agent.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RepositoryService {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final RepositoryFileMapper repositoryFileMapper;

    @Value("${app.upload-dir}")
    private String uploadDir;

    public List<RepositoryFileItem> listFiles() {
        return repositoryFileMapper.selectList(new LambdaQueryWrapper<RepositoryFile>()
                        .orderByDesc(RepositoryFile::getUpdatedAt))
                .stream()
                .map(this::toItem)
                .toList();
    }

    public FileUploadResponse upload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException("上传文件不能为空");
        }

        String originalName = Path.of(file.getOriginalFilename() == null ? "unknown" : file.getOriginalFilename())
                .getFileName()
                .toString();
        String fileId = IdUtil.uuid();
        String storageName = fileId + "-" + originalName;
        Path target = Path.of(uploadDir).toAbsolutePath().normalize().resolve(storageName);

        try {
            Files.createDirectories(target.getParent());
            file.transferTo(target);
        } catch (IOException exception) {
            throw new BizException("文件保存失败：" + exception.getMessage());
        }

        LocalDateTime now = LocalDateTime.now();
        RepositoryFile repositoryFile = new RepositoryFile();
        repositoryFile.setFileId(fileId);
        repositoryFile.setFileName(originalName);
        repositoryFile.setStoragePath(target.toString());
        repositoryFile.setFileSize(file.getSize());
        repositoryFile.setUserId("default");
        repositoryFile.setCreatedAt(now);
        repositoryFile.setUpdatedAt(now);
        repositoryFileMapper.insert(repositoryFile);

        return new FileUploadResponse(fileId);
    }

    public void deleteByFileName(String fileName) {
        RepositoryFile file = repositoryFileMapper.selectOne(new LambdaQueryWrapper<RepositoryFile>()
                .eq(RepositoryFile::getFileName, fileName));
        if (file == null) {
            return;
        }

        try {
            Files.deleteIfExists(Path.of(file.getStoragePath()));
        } catch (IOException ignored) {
            // Database state is authoritative for this scaffold.
        }
        repositoryFileMapper.deleteById(file.getId());
    }

    private RepositoryFileItem toItem(RepositoryFile file) {
        return RepositoryFileItem.builder()
                .fileName(file.getFileName())
                .userId(file.getUserId())
                .createdAt(format(file.getCreatedAt()))
                .updatedAt(format(file.getUpdatedAt()))
                .build();
    }

    private String format(LocalDateTime value) {
        return value == null ? null : value.format(FORMATTER);
    }
}
