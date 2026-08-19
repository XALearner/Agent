package com.agent.service;

import com.agent.dto.RagReference;
import com.agent.entity.SessionDocument;
import com.agent.entity.SessionDocumentChunk;
import com.agent.mapper.SessionDocumentChunkMapper;
import com.agent.exception.BizException;
import com.agent.mapper.SessionDocumentMapper;
import com.agent.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.transaction.annotation.Transactional;
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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class RagService {

    private static final int CHUNK_SIZE = 900;
    private static final int CHUNK_OVERLAP = 120;
    private static final int MAX_REFERENCES = 5;
    private static final int MAX_CONTEXT_LENGTH = 6000;
    private static final int EMBEDDING_BATCH_SIZE = 16;

    private final SessionDocumentMapper sessionDocumentMapper;
    private final SessionDocumentChunkMapper sessionDocumentChunkMapper;
    private final DashScopeEmbeddingClient embeddingClient;
    private final ObjectMapper objectMapper;

    @Value("${app.upload-dir}")
    private String uploadDir;

    @Transactional
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
        String text;
        try {
            Files.createDirectories(target.getParent());
            file.transferTo(target);
            text = extractText(target, originalName);
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
        saveChunks(sessionId, document.getId(), split(text), now);
    }

    public List<RagReference> retrieve(String sessionId, String question) {
        if (!StringUtils.hasText(sessionId) || !StringUtils.hasText(question)) {
            return List.of();
        }

        List<Double> queryEmbedding = embeddingClient.embed(question);
        if (queryEmbedding.isEmpty()) {
            return List.of();
        }

        List<SessionDocumentChunk> storedChunks = sessionDocumentChunkMapper.selectList(new LambdaQueryWrapper<SessionDocumentChunk>()
                .eq(SessionDocumentChunk::getSessionId, sessionId));
        if (storedChunks.isEmpty()) {
            return List.of();
        }

        List<Long> documentIds = storedChunks.stream()
                .map(SessionDocumentChunk::getDocumentId)
                .distinct()
                .toList();
        Map<Long, SessionDocument> documents = new HashMap<>();
        sessionDocumentMapper.selectBatchIds(documentIds)
                .forEach(document -> documents.put(document.getId(), document));

        List<ScoredChunk> scoredChunks = new ArrayList<>();
        for (SessionDocumentChunk chunk : storedChunks) {
            SessionDocument document = documents.get(chunk.getDocumentId());
            if (document == null) {
                continue;
            }
            double score = cosineSimilarity(queryEmbedding, parseEmbedding(chunk.getEmbedding()));
            if (score > 0) {
                scoredChunks.add(new ScoredChunk(document, chunk, score));
            }
        }

        return scoredChunks.stream()
                .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed())
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
        SessionDocumentChunk chunk = scoredChunk.chunk();
        String id = document.getId() + "-" + chunk.getChunkIndex();
        return RagReference.builder()
                .id(id)
                .documentId(String.valueOf(document.getId()))
                .documentName(document.getDocumentName())
                .contentWithWeight(chunk.getContent())
                .positions(List.of(List.of(chunk.getStartOffset(), chunk.getEndOffset())))
                .build();
    }

    private void saveChunks(String sessionId, Long documentId, List<TextChunk> chunks, LocalDateTime now) {
        for (int start = 0; start < chunks.size(); start += EMBEDDING_BATCH_SIZE) {
            int end = Math.min(start + EMBEDDING_BATCH_SIZE, chunks.size());
            List<TextChunk> batch = chunks.subList(start, end);
            List<List<Double>> embeddings = embeddingClient.embed(batch.stream()
                    .map(TextChunk::content)
                    .toList());

            for (int index = 0; index < batch.size(); index++) {
                TextChunk textChunk = batch.get(index);
                SessionDocumentChunk chunk = new SessionDocumentChunk();
                chunk.setDocumentId(documentId);
                chunk.setSessionId(sessionId);
                chunk.setChunkIndex(textChunk.index());
                chunk.setStartOffset(textChunk.start());
                chunk.setEndOffset(textChunk.end());
                chunk.setContent(textChunk.content());
                chunk.setEmbedding(toJson(embeddings.get(index)));
                chunk.setCreatedAt(now);
                chunk.setUpdatedAt(now);
                sessionDocumentChunkMapper.insert(chunk);
            }
        }
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

    private String toJson(List<Double> embedding) {
        try {
            return objectMapper.writeValueAsString(embedding);
        } catch (JsonProcessingException exception) {
            throw new BizException("向量序列化失败：" + exception.getMessage());
        }
    }

    private List<Double> parseEmbedding(String embedding) {
        try {
            return objectMapper.readValue(embedding, new TypeReference<>() {
            });
        } catch (JsonProcessingException exception) {
            return List.of();
        }
    }

    private double cosineSimilarity(List<Double> left, List<Double> right) {
        if (left.isEmpty() || left.size() != right.size()) {
            return 0;
        }

        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int index = 0; index < left.size(); index++) {
            double leftValue = left.get(index);
            double rightValue = right.get(index);
            dot += leftValue * rightValue;
            leftNorm += leftValue * leftValue;
            rightNorm += rightValue * rightValue;
        }
        if (leftNorm == 0 || rightNorm == 0) {
            return 0;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
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

    private record ScoredChunk(SessionDocument document, SessionDocumentChunk chunk, double score) {
    }
}
