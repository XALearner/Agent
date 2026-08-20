package com.agent.service;

import com.agent.config.McpProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpClientServiceTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void listsAndCallsHttpMcpTools() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/mcp", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String response;
            if (body.contains("\"method\":\"initialize\"")) {
                response = """
                        {"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18","capabilities":{},"serverInfo":{"name":"mock","version":"1.0.0"}}}
                        """;
            } else if (body.contains("\"method\":\"notifications/initialized\"")) {
                response = "";
            } else if (body.contains("\"method\":\"tools/list\"")) {
                response = """
                        {"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"echo","description":"Echo text","inputSchema":{"type":"object","required":["text"],"properties":{"text":{"type":"string"}}}}]}}
                        """;
            } else {
                response = """
                        {"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":"hello"}]}}
                        """;
            }
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("Mcp-Session-Id", "session-1");
            exchange.sendResponseHeaders(200, response.getBytes(StandardCharsets.UTF_8).length);
            exchange.getResponseBody().write(response.getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        server.start();

        McpProperties properties = new McpProperties();
        properties.setEnabled(true);
        McpProperties.Server mcpServer = new McpProperties.Server();
        mcpServer.setUrl("http://localhost:" + server.getAddress().getPort() + "/mcp");
        properties.setServers(Map.of("mock", mcpServer));
        McpClientService service = new McpClientService(properties, new ObjectMapper());

        List<Map<String, Object>> specifications = service.toolSpecifications();

        assertThat(specifications).hasSize(1);
        assertThat(specifications.get(0)).extracting("type").isEqualTo("function");
        assertThat(service.supports("mcp__mock__echo")).isTrue();
        assertThat(service.callTool("mcp__mock__echo", new ObjectMapper().readTree("{\"text\":\"hello\"}")))
                .contains("hello");
    }
}
