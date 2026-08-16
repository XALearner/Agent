package com.agent.llm;

import java.io.IOException;

@FunctionalInterface
public interface ChatStreamConsumer {

    void accept(String content, boolean thinking) throws IOException;
}
