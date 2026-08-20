package com.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "mcp")
public class McpProperties {

    private boolean enabled;
    private Map<String, Server> servers = new LinkedHashMap<>();

    @Data
    public static class Server {

        private boolean enabled = true;
        private String url;
        private String bearerToken;
        private Map<String, String> headers = new LinkedHashMap<>();
    }
}
