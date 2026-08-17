package com.agent.service;

import com.agent.dto.RagReference;
import com.agent.entity.SessionDocument;
import com.agent.exception.BizException;
import com.agent.mapper.SessionDocumentMapper;
import com.agent.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.BreakIterator;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class RagService {

    private static final int CHUNK_SIZE = 900;
    private static final int CHUNK_OVERLAP = 120;
    private static final int MAX_REFERENCES = 5;
    private static final int MAX_CONTEXT_LENGTH = 6000;
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[\\p{IsHan}]{2,}|[A-Za-z0-9_]{2,}");

    private final SessionDocumentMapper sessionDocumentMapper;

    @Value("${app.upload-dir}")
    private String uploadDir;

    public void saveSessionDocument(String sessionId, MultipartFile file) {
        if (!StringUtils.hasText(sessionId)) {
            throw new BizException("session_id 不能为空");
        }
        if (file == null || file.isEmpty()) {
            throw new BizException("上传文件不能为空");
        }

        String originalName = Path.of(file.getOriginalFilename() == null ? "unknown" : file.getOriginalFilename())
                .getFileName()
                .toString();
        Path target = sessionDocumentPath(sessionId, originalName);
        try {
            Files.createDirectories(target.getParent());
            file.transferTo(target);
            extractText(target, originalName);
        } catch (IOException exception) {
            throw new BizException("文档解析失败：" + exception.getMessage());
        }

        LocalDateTime now = LocalDateTime.now();
        SessionDocument document = new SessionDocument();
        document.setSessionId(sessionId);
        document.setDocumentName(originalName);
        document.setDocumentType(extension(originalName));
        document.setFileSize(file.getSize());
        document.setUploadTime(now);
        document.setCreatedAt(now);
        document.setUpdatedAt(now);
        sessionDocumentMapper.insert(document);
    }

    public List<RagReference> retrieve(String sessionId, String question) {
        if (!StringUtils.hasText(sessionId) || !StringUtils.hasText(question)) {
            return List.of();
        }

        List<SessionDocument> documents = sessionDocumentMapper.selectList(new LambdaQueryWrapper<SessionDocument>()
                .eq(SessionDocument::getSessionId, sessionId)
                .orderByDesc(SessionDocument::getUpdatedAt));
        if (documents.isEmpty()) {
            return List.of();
        }

        Set<String> queryTokens = tokens(question);
        List<ScoredChunk> chunks = new ArrayList<>();
        for (SessionDocument document : documents) {
            Path path = sessionDocumentPath(sessionId, document.getDocumentName());
            if (!Files.exists(path)) {
                continue;
            }
            try {
                String text = extractText(path, document.getDocumentName());
                split(text).forEach(chunk -> {
                    int score = score(queryTokens, chunk.content());
                    if (score > 0) {
                        chunks.add(new ScoredChunk(document, chunk, score));
                    }
                });
            } catch (IOException ignored) {
                // Ignore unreadable documents so one bad file does not break chat.
            }
        }

        return chunks.stream()
                .sorted(Comparator.comparingInt(ScoredChunk::score).reversed())
                .limit(MAX_REFERENCES)
                .map(this::toReference)
                .toList();
    }

    public String augmentMessage(String question, List<RagReference> references) {
        if (references == null || references.isEmpty()) {
            return question;
        }

        StringBuilder context = new StringBuilder();
        for (int i = 0; i < references.size(); i++) {
            RagReference reference = references.get(i);
            context.append("[资料").append(i + 1).append("] ")
                    .append(reference.getDocumentName()).append("\n")
                    .append(reference.getContentWithWeight()).append("\n\n");
            if (context.length() >= MAX_CONTEXT_LENGTH) {
                break;
            }
        }

        return """
                请优先根据下面的资料回答用户问题。若资料不足以回答，请明确说明资料中没有相关信息，再给出你基于常识的补充。

                # 资料
                %s
                # 用户问题
                %s
                """.formatted(context.substring(0, Math.min(context.length(), MAX_CONTEXT_LENGTH)), question);
    }

    private RagReference toReference(ScoredChunk scoredChunk) {
        SessionDocument document = scoredChunk.document();
        TextChunk chunk = scoredChunk.chunk();
        String id = document.getId() + "-" + chunk.index();
        return RagReference.builder()
                .id(id)
                .documentId(String.valueOf(document.getId()))
                .documentName(document.getDocumentName())
                .contentWithWeight(chunk.content())
                .positions(List.of(List.of(chunk.start(), chunk.end())))
                .build();
    }

    private Path sessionDocumentPath(String sessionId, String fileName) {
        String safeSessionId = safeName(sessionId);
        String safeFileName = safeName(fileName);
        return Path.of(uploadDir).toAbsolutePath().normalize()
                .resolve("session-docs")
                .resolve(safeSessionId)
                .resolve(safeFileName);
    }

    private String extractText(Path path, String fileName) throws IOException {
        String ext = extension(fileName);
        if ("pdf".equals(ext)) {
            try (PDDocument document = Loader.loadPDF(path.toFile())) {
                return new PDFTextStripper().getText(document);
            }
        }
        if ("docx".equals(ext)) {
            try (InputStream inputStream = Files.newInputStream(path);
                 XWPFDocument document = new XWPFDocument(inputStream)) {
                return document.getParagraphs().stream()
                        .map(XWPFParagraph::getText)
                        .filter(StringUtils::hasText)
                        .reduce("", (left, right) -> left + right + "\n");
            }
        }
        if ("doc".equals(ext)) {
            try (InputStream inputStream = Files.newInputStream(path);
                 HWPFDocument document = new HWPFDocument(inputStream);
                 WordExtractor extractor = new WordExtractor(document)) {
                return extractor.getText();
            }
        }
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private List<TextChunk> split(String text) {
        String normalized = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        if (!StringUtils.hasText(normalized)) {
            return List.of();
        }

        List<TextChunk> chunks = new ArrayList<>();
        int start = 0;
        int index = 1;
        while (start < normalized.length()) {
            int end = Math.min(start + CHUNK_SIZE, normalized.length());
            end = adjustEnd(normalized, start, end);
            chunks.add(new TextChunk(index++, start, end, normalized.substring(start, end)));
            if (end >= normalized.length()) {
                break;
            }
            start = Math.max(end - CHUNK_OVERLAP, start + 1);
        }
        return chunks;
    }

    private int adjustEnd(String text, int start, int end) {
        if (end >= text.length()) {
            return text.length();
        }
        BreakIterator iterator = BreakIterator.getSentenceInstance(Locale.CHINA);
        iterator.setText(text.substring(start, end));
        int last = iterator.last();
        if (last > CHUNK_SIZE / 2) {
            return start + last;
        }
        return end;
    }

    private int score(Set<String> queryTokens, String content) {
        if (queryTokens.isEmpty()) {
            return 1;
        }
        String lower = content.toLowerCase(Locale.ROOT);
        int score = 0;
        for (String token : queryTokens) {
            if (lower.contains(token)) {
                score += token.length();
            }
        }
        return score;
    }

    private Set<String> tokens(String text) {
        Set<String> result = new HashSet<>();
        Matcher matcher = TOKEN_PATTERN.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            result.add(matcher.group());
        }
        return result;
    }

    private String extension(String fileName) {
        int index = fileName.lastIndexOf('.');
        if (index < 0 || index == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(index + 1).toLowerCase(Locale.ROOT);
    }

    private String safeName(String value) {
        String safe = value.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return StringUtils.hasText(safe) ? safe : IdUtil.uuid();
    }

    private record TextChunk(int index, int start, int end, String content) {
    }

    private record ScoredChunk(SessionDocument document, TextChunk chunk, int score) {
    }
}
