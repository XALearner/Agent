package com.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "qwen")
public class QwenProperties {

    private String apiKey;
    private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private String model = "qwen-plus";
    private String embeddingModel = "text-embedding-v3";
    private boolean rerankEnabled = true;
    private String rerankUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1/reranks";
    private String rerankModel = "qwen3-rerank";
    private String rerankInstruct = "Given a web search query, retrieve relevant passages that answer the query.";
    private String systemPrompt = "你是一个专业、可靠的中文 AI 助手。";
}
