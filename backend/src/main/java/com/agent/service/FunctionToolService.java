package com.agent.service;

import com.agent.dto.RagReference;
import com.agent.exception.BizException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class FunctionToolService {

    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");

    private final ObjectMapper objectMapper;
    private final RagService ragService;

    public List<Map<String, Object>> specifications(boolean includeDocumentSearch) {
        List<Map<String, Object>> tools = new java.util.ArrayList<>();
        tools.add(Map.of(
                "type", "function",
                "function", Map.of(
                        "name", "get_current_datetime",
                        "description", "获取当前日期时间。适合回答今天、现在、当前时间等问题。",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "timezone", Map.of(
                                                "type", "string",
                                                "description", "IANA 时区名称，例如 Asia/Shanghai。未提供时使用 Asia/Shanghai。"
                                        )
                                )
                        )
                )
        ));
        if (includeDocumentSearch) {
            tools.add(Map.of(
                    "type", "function",
                    "function", Map.of(
                            "name", "search_session_documents",
                            "description", "在当前会话已上传的文档中检索与问题相关的资料片段。适合需要查阅资料、合同、PDF、Word 或知识库内容时使用。",
                            "parameters", Map.of(
                                    "type", "object",
                                    "required", List.of("query"),
                                    "properties", Map.of(
                                            "query", Map.of(
                                                    "type", "string",
                                                    "description", "用于检索当前会话文档的自然语言问题或关键词。"
                                            )
                                    )
                            )
                    )
            ));
        }
        return tools;
    }

    public String execute(String name, String arguments, String sessionId) {
        JsonNode root = parseArguments(arguments);
        return switch (name) {
            case "get_current_datetime" -> currentDatetime(root);
            case "search_session_documents" -> searchSessionDocuments(root, sessionId);
            default -> throw new BizException("不支持的函数调用：" + name);
        };
    }

    private String currentDatetime(JsonNode arguments) {
        String timezone = arguments.path("timezone").asText("");
        ZoneId zoneId = StringUtils.hasText(timezone) ? ZoneId.of(timezone) : DEFAULT_ZONE;
        OffsetDateTime now = OffsetDateTime.now(zoneId);
        return toJson(Map.of(
                "timezone", zoneId.getId(),
                "datetime", now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        ));
    }

    private String searchSessionDocuments(JsonNode arguments, String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            throw new BizException("当前请求没有 session_id，无法检索会话文档");
        }
        String query = arguments.path("query").asText("");
        if (!StringUtils.hasText(query)) {
            throw new BizException("search_session_documents 缺少 query 参数");
        }
        List<RagReference> references = ragService.retrieve(sessionId, query);
        return toJson(Map.of(
                "documents", references
        ));
    }

    private JsonNode parseArguments(String arguments) {
        if (!StringUtils.hasText(arguments)) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(arguments);
        } catch (JsonProcessingException exception) {
            throw new BizException("函数参数解析失败：" + exception.getMessage());
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BizException("函数结果序列化失败：" + exception.getMessage());
        }
    }
}
