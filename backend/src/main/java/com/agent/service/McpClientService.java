package com.agent.service;

import com.agent.config.McpProperties;
import com.agent.exception.BizException;
import com.fasterxml.jackson.core.JsonProcessingException;
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class McpClientService {

    private static final String PROTOCOL_VERSION = "2025-06-18";
    private static final Pattern TOOL_NAME_CHARS = Pattern.compile("[^A-Za-z0-9_-]");

    private final McpProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    private final AtomicLong requestId = new AtomicLong(1);
    private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();
    private final Map<String, ToolReference> toolReferences = new ConcurrentHashMap<>();

    public List<Map<String, Object>> toolSpecifications() {
        if (!properties.isEnabled()) {
            return List.of();
        }
        List<Map<String, Object>> specifications = new ArrayList<>();
        toolReferences.clear();
        properties.getServers().forEach((serverName, server) -> {
            if (!isConfigured(server)) {
                return;
            }
            for (McpTool tool : listTools(serverName, server)) {
                String functionName = functionName(serverName, tool.name());
                toolReferences.put(functionName, new ToolReference(serverName, tool.name()));
                specifications.add(Map.of(
                        "type", "function",
                        "function", Map.of(
                                "name", functionName,
                                "description", StringUtils.hasText(tool.description())
                                        ? tool.description()
                                        : "MCP tool " + tool.name() + " from server " + serverName,
                                "parameters", tool.inputSchema()
                        )
                ));
            }
        });
        return specifications;
    }

    public boolean supports(String functionName) {
        return toolReferences.containsKey(functionName) || parseFunctionName(functionName).isPresent();
    }

    public String callTool(String functionName, JsonNode arguments) {
        ToolReference reference = toolReferences.get(functionName);
        if (reference == null) {
            reference = parseFunctionName(functionName)
                    .orElseThrow(() -> new BizException("不支持的 MCP 工具调用：" + functionName));
        }
        McpProperties.Server server = properties.getServers().get(reference.serverName());
        if (!isConfigured(server)) {
            throw new BizException("MCP server 未配置或未启用：" + reference.serverName());
        }

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", reference.toolName());
        params.put("arguments", objectMapper.convertValue(arguments, Map.class));
        JsonNode result = sendRequest(reference.serverName(), server, "tools/call", params);
        return toJson(result);
    }

    private List<McpTool> listTools(String serverName, McpProperties.Server server) {
        JsonNode result = sendRequest(serverName, server, "tools/list", Collections.emptyMap());
        JsonNode toolsNode = result.path("tools");
        if (!toolsNode.isArray()) {
            return List.of();
        }

        List<McpTool> tools = new ArrayList<>();
        for (JsonNode toolNode : toolsNode) {
            String name = toolNode.path("name").asText("");
            if (!StringUtils.hasText(name)) {
                continue;
            }
            JsonNode inputSchema = toolNode.path("inputSchema");
            Object schema = inputSchema.isObject()
                    ? objectMapper.convertValue(inputSchema, Map.class)
                    : Map.of("type", "object", "properties", Map.of());
            tools.add(new McpTool(name, toolNode.path("description").asText(""), schema));
        }
        return tools;
    }

    private JsonNode sendRequest(String serverName, McpProperties.Server server, String method, Object params) {
        ensureInitialized(serverName, server);
        return sendJsonRpc(serverName, server, method, params, true);
    }

    private void ensureInitialized(String serverName, McpProperties.Server server) {
        SessionState existing = sessions.get(serverName);
        if (existing != null && existing.initialized()) {
            return;
        }

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("protocolVersion", PROTOCOL_VERSION);
        params.put("capabilities", Map.of());
        params.put("clientInfo", Map.of(
                "name", "agent-backend",
                "version", "0.0.1"
        ));
        sendJsonRpc(serverName, server, "initialize", params, true);
        sendJsonRpc(serverName, server, "notifications/initialized", null, false);
        sessions.compute(serverName, (key, state) -> new SessionState(state == null ? null : state.sessionId(), true));
    }

    private JsonNode sendJsonRpc(String serverName, McpProperties.Server server, String method, Object params, boolean expectResponse) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jsonrpc", "2.0");
        if (expectResponse) {
            payload.put("id", requestId.getAndIncrement());
        }
        payload.put("method", method);
        if (params != null) {
            payload.put("params", params);
        }

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(server.getUrl()))
                .timeout(Duration.ofMinutes(3))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(toJson(payload)));
        if (StringUtils.hasText(server.getBearerToken())) {
            requestBuilder.header("Authorization", "Bearer " + server.getBearerToken());
        }
        server.getHeaders().forEach(requestBuilder::header);
        SessionState state = sessions.get(serverName);
        if (state != null && StringUtils.hasText(state.sessionId())) {
            requestBuilder.header("Mcp-Session-Id", state.sessionId());
        }

        HttpResponse<String> response;
        try {
            response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BizException("MCP 调用被中断：" + serverName);
        } catch (IOException exception) {
            throw new BizException("MCP 调用失败：" + serverName + " - " + exception.getMessage());
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new BizException("MCP 调用失败：" + serverName + " - " + response.body());
        }
        response.headers().firstValue("mcp-session-id")
                .ifPresent(sessionId -> sessions.put(serverName, new SessionState(sessionId, false)));
        if (!expectResponse || !StringUtils.hasText(response.body())) {
            return objectMapper.createObjectNode();
        }

        JsonNode root = parseJsonRpcBody(response.body());
        JsonNode error = root.path("error");
        if (!error.isMissingNode()) {
            throw new BizException("MCP 调用失败：" + serverName + " - " + error.toString());
        }
        return root.path("result");
    }

    private JsonNode parseJsonRpcBody(String body) {
        String json = body.trim();
        if (json.startsWith("data:")) {
            for (String line : json.split("\\R")) {
                if (line.startsWith("data:")) {
                    String data = line.substring("data:".length()).trim();
                    if (StringUtils.hasText(data) && !"[DONE]".equals(data)) {
                        json = data;
                        break;
                    }
                }
            }
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new BizException("MCP 返回格式解析失败：" + exception.getMessage());
        }
    }

    private boolean isConfigured(McpProperties.Server server) {
        return server != null && server.isEnabled() && StringUtils.hasText(server.getUrl());
    }

    private String functionName(String serverName, String toolName) {
        return "mcp__" + sanitize(serverName) + "__" + sanitize(toolName);
    }

    private Optional<ToolReference> parseFunctionName(String functionName) {
        if (!StringUtils.hasText(functionName) || !functionName.startsWith("mcp__")) {
            return Optional.empty();
        }
        String[] parts = functionName.split("__", 3);
        if (parts.length != 3 || !properties.getServers().containsKey(parts[1])) {
            return Optional.empty();
        }
        return Optional.of(new ToolReference(parts[1], parts[2]));
    }

    private String sanitize(String value) {
        String sanitized = TOOL_NAME_CHARS.matcher(value).replaceAll("_");
        return StringUtils.hasText(sanitized) ? sanitized : "unnamed";
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BizException("MCP JSON 序列化失败：" + exception.getMessage());
        }
    }

    private record SessionState(String sessionId, boolean initialized) {
    }

    private record ToolReference(String serverName, String toolName) {
    }

    private record McpTool(String name, String description, Object inputSchema) {
    }
}
