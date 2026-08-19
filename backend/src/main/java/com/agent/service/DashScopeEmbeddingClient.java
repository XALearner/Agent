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
public class DashScopeEmbeddingClient {

    private final QwenProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    public List<List<Double>> embed(List<String> inputs) {
        if (!StringUtils.hasText(properties.getApiKey())) {
            throw new BizException("缺少 DASHSCOPE_API_KEY，请先配置 Qwen API Key");
        }
        if (inputs == null || inputs.isEmpty()) {
            return List.of();
        }

        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "model", properties.getEmbeddingModel(),
                    "input", inputs
            ));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(embeddingsUrl()))
                    .timeout(Duration.ofMinutes(2))
                    .header("Authorization", "Bearer " + properties.getApiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BizException("Embedding 调用失败：" + response.body());
            }
            return parseEmbeddings(response.body(), inputs.size());
        } catch (IOException exception) {
            throw new BizException("Embedding 调用失败：" + exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BizException("Embedding 调用被中断");
        }
    }

    public List<Double> embed(String input) {
        List<List<Double>> embeddings = embed(List.of(input));
        return embeddings.isEmpty() ? List.of() : embeddings.get(0);
    }

    private List<List<Double>> parseEmbeddings(String body, int expectedSize) throws IOException {
        JsonNode data = objectMapper.readTree(body).path("data");
        if (!data.isArray() || data.size() != expectedSize) {
            throw new BizException("Embedding 返回格式异常");
        }

        List<List<Double>> embeddings = new ArrayList<>();
        for (JsonNode item : data) {
            JsonNode embeddingNode = item.path("embedding");
            if (!embeddingNode.isArray()) {
                throw new BizException("Embedding 返回格式异常");
            }
            List<Double> embedding = new ArrayList<>();
            for (JsonNode value : embeddingNode) {
                embedding.add(value.asDouble());
            }
            embeddings.add(embedding);
        }
        return embeddings;
    }

    private String embeddingsUrl() {
        String baseUrl = properties.getBaseUrl();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl + "/embeddings";
    }
}
