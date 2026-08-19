package com.agent.service;

import com.agent.dto.RagReference;
import com.agent.entity.SessionDocument;
import com.agent.exception.BizException;
import com.agent.mapper.SessionDocumentMapper;
import com.agent.service.ElasticsearchVectorStore.VectorChunk;
import com.agent.service.ElasticsearchVectorStore.VectorSearchResult;
import com.agent.util.IdUtil;
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
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class RagService {

    private static final int CHUNK_SIZE = 900;
    private static final int CHUNK_OVERLAP = 120;
    private static final int MAX_REFERENCES = 5;
    private static final int KEYWORD_RERANK_CANDIDATES = 20;
    private static final double VECTOR_SCORE_WEIGHT = 0.75;
    private static final double KEYWORD_SCORE_WEIGHT = 0.25;
    private static final int MAX_CONTEXT_LENGTH = 6000;
    private static final int EMBEDDING_BATCH_SIZE = 16;

    private final SessionDocumentMapper sessionDocumentMapper;
    private final DashScopeEmbeddingClient embeddingClient;
    private final ElasticsearchVectorStore vectorStore;
    private final KeywordExtractor keywordExtractor;

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
        indexChunks(sessionId, document, split(text));
    }

    public List<RagReference> retrieve(String sessionId, String question) {
        if (!StringUtils.hasText(sessionId) || !StringUtils.hasText(question)) {
            return List.of();
        }

        List<Double> queryEmbedding = embeddingClient.embed(question);
        if (queryEmbedding.isEmpty()) {
            return List.of();
        }

        List<String> questionKeywords = keywordExtractor.extract(question);
        return vectorStore.search(sessionId, queryEmbedding, KEYWORD_RERANK_CANDIDATES)
                .stream()
                .sorted(Comparator.comparingDouble(result -> -hybridScore(result, questionKeywords)))
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

    private RagReference toReference(VectorSearchResult result) {
        String id = result.documentId() + "-" + result.chunkIndex();
        return RagReference.builder()
                .id(id)
                .documentId(String.valueOf(result.documentId()))
                .documentName(result.documentName())
                .contentWithWeight(result.content())
                .positions(List.of(List.of(result.startOffset(), result.endOffset())))
                .build();
    }

    private void indexChunks(String sessionId, SessionDocument document, List<TextChunk> chunks) {
        for (int start = 0; start < chunks.size(); start += EMBEDDING_BATCH_SIZE) {
            int end = Math.min(start + EMBEDDING_BATCH_SIZE, chunks.size());
            List<TextChunk> batch = chunks.subList(start, end);
            List<List<Double>> embeddings = embeddingClient.embed(batch.stream()
                    .map(TextChunk::content)
                    .toList());

            List<VectorChunk> vectorChunks = new ArrayList<>();
            for (int index = 0; index < batch.size(); index++) {
                TextChunk textChunk = batch.get(index);
                vectorChunks.add(new VectorChunk(
                        sessionId,
                        document.getId(),
                        document.getDocumentName(),
                        textChunk.index(),
                        textChunk.start(),
                        textChunk.end(),
                        textChunk.content(),
                        keywordExtractor.extract(textChunk.content()),
                        embeddings.get(index)
                ));
            }
            vectorStore.indexChunks(vectorChunks);
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

    private double hybridScore(VectorSearchResult result, List<String> questionKeywords) {
        double keywordScore = keywordExtractor.similarity(questionKeywords, result.keywords());
        return result.score() * VECTOR_SCORE_WEIGHT + keywordScore * KEYWORD_SCORE_WEIGHT;
    }

    private record TextChunk(int index, int start, int end, String content) {
    }
}
