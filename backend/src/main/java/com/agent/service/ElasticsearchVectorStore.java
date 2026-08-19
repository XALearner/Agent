package com.agent.service;

import com.agent.config.ElasticsearchProperties;
import com.agent.exception.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ElasticsearchVectorStore {

    private static final String VECTOR_FIELD = "embedding";

    private final ElasticsearchProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public void indexChunks(List<VectorChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }

        ensureIndex(chunks.get(0).embedding().size());

        StringBuilder payload = new StringBuilder();
        for (VectorChunk chunk : chunks) {
            payload.append(toJson(Map.of(
                    "index", Map.of(
                            "_index", properties.getVectorIndex(),
                            "_id", documentKey(chunk.documentId(), chunk.chunkIndex())
                    )
            ))).append("\n");
            payload.append(toJson(toSource(chunk))).append("\n");
        }

        HttpResponse<String> response = send("POST", "/_bulk?refresh=wait_for", payload.toString(), "application/x-ndjson");
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new BizException("ES 向量写入失败：" + response.body());
        }
        try {
            JsonNode root = objectMapper.readTree(response.body());
            if (root.path("errors").asBoolean(false)) {
                throw new BizException("ES 向量写入部分失败：" + response.body());
            }
        } catch (IOException exception) {
            throw new BizException("ES 向量写入响应解析失败：" + exception.getMessage());
        }
    }

    public List<VectorSearchResult> search(String sessionId, List<Double> queryEmbedding, int limit) {
        if (!StringUtils.hasText(sessionId) || queryEmbedding == null || queryEmbedding.isEmpty() || limit <= 0) {
            return List.of();
        }

        ensureIndex(queryEmbedding.size());

        Map<String, Object> knn = new LinkedHashMap<>();
        knn.put("field", VECTOR_FIELD);
        knn.put("query_vector", queryEmbedding);
        knn.put("k", limit);
        knn.put("num_candidates", Math.max(limit, properties.getNumCandidates()));
        knn.put("filter", Map.of("term", Map.of("session_id", sessionId)));

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("knn", knn);
        request.put("size", limit);
        request.put("_source", List.of(
                "document_id",
                "document_name",
                "chunk_index",
                "start_offset",
                "end_offset",
                "content",
                "keywords"
        ));

        HttpResponse<String> response = send("POST", "/" + indexPath() + "/_search", toJson(request));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new BizException("ES 向量检索失败：" + response.body());
        }
        return parseSearchResults(response.body());
    }

    private void ensureIndex(int vectorDimensions) {
        HttpResponse<String> existsResponse = send("HEAD", "/" + indexPath(), "");
        if (existsResponse.statusCode() == 200) {
            return;
        }
        if (existsResponse.statusCode() != 404) {
            throw new BizException("ES 索引检查失败：" + existsResponse.body());
        }

        Map<String, Object> embeddingMapping = new LinkedHashMap<>();
        embeddingMapping.put("type", "dense_vector");
        embeddingMapping.put("dims", vectorDimensions);
        embeddingMapping.put("index", true);
        embeddingMapping.put("similarity", "cosine");

        Map<String, Object> propertiesMapping = new LinkedHashMap<>();
        propertiesMapping.put("session_id", Map.of("type", "keyword"));
        propertiesMapping.put("document_id", Map.of("type", "long"));
        propertiesMapping.put("document_name", Map.of("type", "keyword"));
        propertiesMapping.put("chunk_index", Map.of("type", "integer"));
        propertiesMapping.put("start_offset", Map.of("type", "integer"));
        propertiesMapping.put("end_offset", Map.of("type", "integer"));
        propertiesMapping.put("content", Map.of("type", "text"));
        propertiesMapping.put("keywords", Map.of("type", "keyword"));
        propertiesMapping.put(VECTOR_FIELD, embeddingMapping);

        Map<String, Object> mapping = Map.of(
                "mappings", Map.of("properties", propertiesMapping)
        );

        HttpResponse<String> createResponse = send("PUT", "/" + indexPath(), toJson(mapping));
        if (createResponse.statusCode() < 200 || createResponse.statusCode() >= 300) {
            throw new BizException("ES 向量索引创建失败：" + createResponse.body());
        }
    }

    private List<VectorSearchResult> parseSearchResults(String body) {
        try {
            JsonNode hits = objectMapper.readTree(body).path("hits").path("hits");
            if (!hits.isArray()) {
                return List.of();
            }

            List<VectorSearchResult> results = new ArrayList<>();
            for (JsonNode hit : hits) {
                JsonNode source = hit.path("_source");
                results.add(new VectorSearchResult(
                        source.path("document_id").asLong(),
                        source.path("document_name").asText(),
                        source.path("chunk_index").asInt(),
                        source.path("start_offset").asInt(),
                        source.path("end_offset").asInt(),
                        source.path("content").asText(),
                        parseKeywords(source.path("keywords")),
                        hit.path("_score").asDouble()
                ));
            }
            return results;
        } catch (IOException exception) {
            throw new BizException("ES 向量检索响应解析失败：" + exception.getMessage());
        }
    }

    private Map<String, Object> toSource(VectorChunk chunk) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("session_id", chunk.sessionId());
        source.put("document_id", chunk.documentId());
        source.put("document_name", chunk.documentName());
        source.put("chunk_index", chunk.chunkIndex());
        source.put("start_offset", chunk.startOffset());
        source.put("end_offset", chunk.endOffset());
        source.put("content", chunk.content());
        source.put("keywords", chunk.keywords());
        source.put(VECTOR_FIELD, chunk.embedding());
        return source;
    }

    private List<String> parseKeywords(JsonNode keywordsNode) {
        if (!keywordsNode.isArray()) {
            return List.of();
        }
        List<String> keywords = new ArrayList<>();
        for (JsonNode keyword : keywordsNode) {
            String value = keyword.asText();
            if (StringUtils.hasText(value)) {
                keywords.add(value);
            }
        }
        return keywords;
    }

    private HttpResponse<String> send(String method, String path, String body) {
        return send(method, path, body, "application/json");
    }

    private HttpResponse<String> send(String method, String path, String body, String contentType) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl() + path))
                    .timeout(Duration.ofMinutes(2))
                    .header("Content-Type", contentType);
            applyAuthorization(builder);
            if ("HEAD".equals(method)) {
                builder.method("HEAD", HttpRequest.BodyPublishers.noBody());
            } else {
                builder.method(method, HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
            }
            return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException exception) {
            throw new BizException("ES 请求失败：" + exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BizException("ES 请求被中断");
        }
    }

    private void applyAuthorization(HttpRequest.Builder builder) {
        if (StringUtils.hasText(properties.getApiKey())) {
            builder.header("Authorization", "ApiKey " + properties.getApiKey());
            return;
        }
        if (StringUtils.hasText(properties.getUsername()) && StringUtils.hasText(properties.getPassword())) {
            String token = properties.getUsername() + ":" + properties.getPassword();
            builder.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8)));
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (IOException exception) {
            throw new BizException("ES 请求序列化失败：" + exception.getMessage());
        }
    }

    private String documentKey(Long documentId, int chunkIndex) {
        return documentId + "-" + chunkIndex;
    }

    private String indexPath() {
        return URLEncoder.encode(properties.getVectorIndex(), StandardCharsets.UTF_8);
    }

    private String baseUrl() {
        String url = properties.getUrl();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    public record VectorChunk(
            String sessionId,
            Long documentId,
            String documentName,
            int chunkIndex,
            int startOffset,
            int endOffset,
            String content,
            List<String> keywords,
            List<Double> embedding
    ) {
    }

    public record VectorSearchResult(
            Long documentId,
            String documentName,
            int chunkIndex,
            int startOffset,
            int endOffset,
            String content,
            List<String> keywords,
            double score
    ) {
    }
}
