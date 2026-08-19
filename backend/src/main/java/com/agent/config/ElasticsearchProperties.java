package com.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "elasticsearch")
public class ElasticsearchProperties {

    private String url = "http://localhost:9200";
    private String username;
    private String password;
    private String apiKey;
    private String vectorIndex = "agent-session-document-chunks";
    private int numCandidates = 100;
}
