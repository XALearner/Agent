package com.agent.llm;

import java.io.IOException;

public interface ChatModelClient {

    String provider();

    String streamChat(ChatCompletionRequest request, ChatStreamConsumer consumer) throws IOException;
}
