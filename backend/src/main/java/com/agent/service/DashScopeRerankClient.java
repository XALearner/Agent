package com.agent.service;

import com.agent.config.QwenProperties;
import com.agent.exception.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DashScopeRerankClient {

    private final QwenProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    public boolean isEnabled() {
        return properties.isRerankEnabled() && StringUtils.hasText(properties.getRerankUrl());
    }

    public List<RerankResult> rerank(String query, List<String> documents, int topN) {
        if (!isEnabled() || documents == null || documents.isEmpty() || topN <= 0) {
            return List.of();
        }
        if (!StringUtils.hasText(properties.getApiKey())) {
            throw new BizException("缺少 DASHSCOPE_API_KEY，请先配置 Qwen API Key");
        }

        try {
            String body = objectMapper.writeValueAsString(requestBody(query, documents, topN));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(properties.getRerankUrl()))
                    .timeout(Duration.ofMinutes(2))
                    .header("Authorization", "Bearer " + properties.getApiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BizException("Rerank 调用失败：" + response.body());
            }
            return parseResults(response.body());
        } catch (IOException exception) {
            throw new BizException("Rerank 调用失败：" + exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BizException("Rerank 调用被中断");
        }
    }

    private List<RerankResult> parseResults(String body) throws IOException {
        JsonNode root = objectMapper.readTree(body);
        JsonNode resultsNode = root.path("results");
        if (!resultsNode.isArray()) {
            resultsNode = root.path("output").path("results");
        }
        if (!resultsNode.isArray()) {
            throw new BizException("Rerank 返回格式异常");
        }

        List<RerankResult> results = new ArrayList<>();
        for (JsonNode item : resultsNode) {
            if (!item.has("index") || !item.has("relevance_score")) {
                throw new BizException("Rerank 返回格式异常");
            }
            results.add(new RerankResult(
                    item.path("index").asInt(),
                    item.path("relevance_score").asDouble()
            ));
        }
        return results;
    }

    private Map<String, Object> requestBody(String query, List<String> documents, int topN) {
        int limit = Math.min(topN, documents.size());
        if ("qwen3-rerank".equals(properties.getRerankModel())) {
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("model", properties.getRerankModel());
            body.put("query", query);
            body.put("documents", documents);
            body.put("top_n", limit);
            if (StringUtils.hasText(properties.getRerankInstruct())) {
                body.put("instruct", properties.getRerankInstruct());
            }
            return body;
        }
        return Map.of(
                "model", properties.getRerankModel(),
                "input", Map.of(
                        "query", query,
                        "documents", documents
                ),
                "parameters", Map.of(
                        "return_documents", false,
                        "top_n", limit
                )
        );
    }

    public record RerankResult(int index, double score) {
    }
}
