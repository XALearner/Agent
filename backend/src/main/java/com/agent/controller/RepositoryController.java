package com.agent.controller;

import com.agent.dto.FileUploadResponse;
import com.agent.dto.RepositoryFileItem;
import com.agent.service.RepositoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class RepositoryController {

    private final RepositoryService repositoryService;

    @GetMapping("/get_files")
    public List<RepositoryFileItem> getFiles() {
        return repositoryService.listFiles();
    }

    @PostMapping("/upload_files")
    public Map<String, String> uploadFiles(@RequestParam("files") MultipartFile file) {
        FileUploadResponse response = repositoryService.upload(file);
        return Map.of(
                "status", "success",
                "message", "success",
                "file_id", response.getFileId()
        );
    }

    @DeleteMapping("/delete_file/{fileName}")
    public Map<String, String> deleteFile(@PathVariable String fileName) {
        repositoryService.deleteByFileName(fileName);
        return Map.of("status", "success", "message", "success");
    }
}
