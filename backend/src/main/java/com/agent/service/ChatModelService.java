package com.agent.service;

import com.agent.config.LlmProperties;
import com.agent.exception.BizException;
import com.agent.llm.ChatCompletionRequest;
import com.agent.llm.ChatModelClient;
import com.agent.llm.ChatStreamConsumer;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ChatModelService {

    private final LlmProperties properties;
    private final Map<String, ChatModelClient> clients;

    public ChatModelService(LlmProperties properties, List<ChatModelClient> clients) {
        this.properties = properties;
        this.clients = clients.stream()
                .collect(Collectors.toMap(client -> normalize(client.provider()), Function.identity()));
    }

    public String streamChat(String provider, ChatCompletionRequest request, ChatStreamConsumer consumer) throws IOException {
        String targetProvider = StringUtils.hasText(provider) ? provider : properties.getDefaultProvider();
        ChatModelClient client = clients.get(normalize(targetProvider));
        if (client == null) {
            throw new BizException("不支持的模型供应商：" + targetProvider);
        }
        return client.streamChat(request, consumer);
    }

    private String normalize(String provider) {
        return provider.toLowerCase(Locale.ROOT);
    }
}
