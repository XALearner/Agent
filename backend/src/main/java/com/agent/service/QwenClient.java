package com.agent.service;

import com.agent.config.QwenProperties;
import com.agent.exception.BizException;
import com.agent.llm.ChatCompletionRequest;
import com.agent.llm.ChatModelClient;
import com.agent.llm.ChatStreamConsumer;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

@Service
@RequiredArgsConstructor
public class QwenClient implements ChatModelClient {

    private final QwenProperties properties;
    private final ObjectMapper objectMapper;
    private final FunctionToolService functionToolService;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    @Override
    public String provider() {
        return "qwen";
    }

    @Override
    public String streamChat(ChatCompletionRequest request, ChatStreamConsumer consumer) throws IOException {
        if (!StringUtils.hasText(properties.getApiKey())) {
            throw new BizException("缺少 DASHSCOPE_API_KEY，请先配置 Qwen API Key");
        }

        String model = StringUtils.hasText(request.getModel()) ? request.getModel() : properties.getModel();
        String systemPrompt = StringUtils.hasText(request.getSystemPrompt())
                ? request.getSystemPrompt()
                : properties.getSystemPrompt();

        if (request.isFunctionCallingEnabled()) {
            ToolDecision toolDecision = decideToolCalls(model, systemPrompt, request);
            if (!toolDecision.toolCalls().isEmpty()) {
                return streamChatWithToolResults(model, toolDecision.messages(), consumer);
            }
        }

        return streamChatDirect(model, systemPrompt, request, consumer);
    }

    private String streamChatDirect(String model, String systemPrompt, ChatCompletionRequest request, ChatStreamConsumer consumer) throws IOException {
        StreamingChatModel chatModel = OpenAiStreamingChatModel.builder()
                .baseUrl(properties.getBaseUrl())
                .apiKey(properties.getApiKey())
                .modelName(model)
                .timeout(Duration.ofMinutes(3))
                .accumulateToolCallId(false)
                .build();

        ChatRequest chatRequest = ChatRequest.builder()
                .messages(List.of(
                        SystemMessage.from(systemPrompt),
                        UserMessage.from(request.getMessage())
                ))
                .build();
        StringBuilder answer = new StringBuilder();
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();

        chatModel.chat(chatRequest, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                if (StringUtils.hasText(partialResponse)) {
                    answer.append(partialResponse);
                    accept(partialResponse, false);
                }
            }

            @Override
            public void onPartialThinking(PartialThinking partialThinking) {
                String thinking = partialThinking.text();
                if (StringUtils.hasText(thinking)) {
                    accept(thinking, true);
                }
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                completed.countDown();
            }

            @Override
            public void onError(Throwable throwable) {
                error.set(throwable);
                completed.countDown();
            }

            private void accept(String content, boolean thinking) {
                try {
                    consumer.accept(content, thinking);
                } catch (IOException exception) {
                    error.set(exception);
                    throw new RuntimeException(exception);
                }
            }
        });

        try {
            completed.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Qwen 调用被中断", exception);
        }

        if (error.get() != null) {
            Throwable throwable = error.get();
            if (throwable instanceof IOException ioException) {
                throw ioException;
            }
            throw new BizException("Qwen 调用失败：" + throwable.getMessage());
        }

        return answer.toString();
    }

    private ToolDecision decideToolCalls(String model, String systemPrompt, ChatCompletionRequest request) throws IOException {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(message("system", systemPrompt));
        messages.add(message("user", request.getMessage()));

        List<Map<String, Object>> tools = functionToolService.specifications(StringUtils.hasText(request.getSessionId()));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("messages", messages);
        payload.put("tools", tools);
        payload.put("tool_choice", "auto");
        payload.put("stream", false);

        HttpResponse<String> response = sendChatCompletion(payload);
        JsonNode assistantMessage = parseAssistantMessage(response.body());
        JsonNode toolCallsNode = assistantMessage.path("tool_calls");
        if (!toolCallsNode.isArray() || toolCallsNode.isEmpty()) {
            return new ToolDecision(messages, List.of());
        }

        List<ToolCall> toolCalls = parseToolCalls(toolCallsNode);
        messages.add(objectMapper.convertValue(assistantMessage, new TypeReference<Map<String, Object>>() {
        }));
        for (ToolCall toolCall : toolCalls) {
            String result = functionToolService.execute(toolCall.name(), toolCall.arguments(), request.getSessionId());
            Map<String, Object> toolMessage = new LinkedHashMap<>();
            toolMessage.put("role", "tool");
            toolMessage.put("tool_call_id", toolCall.id());
            toolMessage.put("name", toolCall.name());
            toolMessage.put("content", result);
            messages.add(toolMessage);
        }
        return new ToolDecision(messages, toolCalls);
    }

    private String streamChatWithToolResults(String model, List<Map<String, Object>> messages, ChatStreamConsumer consumer) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("messages", messages);
        payload.put("stream", true);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(chatCompletionsUrl()))
                .timeout(Duration.ofMinutes(3))
                .header("Authorization", "Bearer " + properties.getApiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(toJson(payload)))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Qwen 调用被中断", exception);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new BizException("Qwen 调用失败：" + response.body());
        }
        return parseStreamingResponse(response.body(), consumer);
    }

    private HttpResponse<String> sendChatCompletion(Map<String, Object> payload) throws IOException {
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(chatCompletionsUrl()))
                    .timeout(Duration.ofMinutes(3))
                    .header("Authorization", "Bearer " + properties.getApiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(toJson(payload)))
                    .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BizException("Qwen 调用失败：" + response.body());
            }
            return response;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Qwen 调用被中断", exception);
        }
    }

    private JsonNode parseAssistantMessage(String body) throws IOException {
        JsonNode choices = objectMapper.readTree(body).path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new BizException("Qwen 返回格式异常");
        }
        JsonNode message = choices.get(0).path("message");
        if (message.isMissingNode()) {
            throw new BizException("Qwen 返回格式异常");
        }
        return message;
    }

    private List<ToolCall> parseToolCalls(JsonNode toolCallsNode) {
        List<ToolCall> toolCalls = new ArrayList<>();
        for (JsonNode toolCallNode : toolCallsNode) {
            JsonNode functionNode = toolCallNode.path("function");
            String id = toolCallNode.path("id").asText("");
            String name = functionNode.path("name").asText("");
            String arguments = functionNode.path("arguments").asText("{}");
            if (StringUtils.hasText(id) && StringUtils.hasText(name)) {
                toolCalls.add(new ToolCall(id, name, arguments));
            }
        }
        return toolCalls;
    }

    private String parseStreamingResponse(String body, ChatStreamConsumer consumer) throws IOException {
        StringBuilder answer = new StringBuilder();
        for (String line : body.split("\\R")) {
            if (!line.startsWith("data:")) {
                continue;
            }
            String data = line.substring("data:".length()).trim();
            if ("[DONE]".equals(data) || !StringUtils.hasText(data)) {
                continue;
            }
            JsonNode delta = objectMapper.readTree(data).path("choices").path(0).path("delta");
            String thinking = firstText(delta, "reasoning_content", "reasoning");
            if (StringUtils.hasText(thinking)) {
                consumer.accept(thinking, true);
            }
            String content = firstText(delta, "content");
            if (StringUtils.hasText(content)) {
                answer.append(content);
                consumer.accept(content, false);
            }
        }
        return answer.toString();
    }

    private String firstText(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode value = node.path(fieldName);
            if (value.isTextual()) {
                return value.asText();
            }
        }
        return "";
    }

    private Map<String, Object> message(String role, String content) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    private String chatCompletionsUrl() {
        String baseUrl = properties.getBaseUrl();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl + "/chat/completions";
    }

    private String toJson(Object value) throws IOException {
        return objectMapper.writeValueAsString(value);
    }

    private record ToolDecision(List<Map<String, Object>> messages, List<ToolCall> toolCalls) {
    }

    private record ToolCall(String id, String name, String arguments) {
    }
}
