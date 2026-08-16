package com.agent.service;

import com.agent.config.QwenProperties;
import com.agent.exception.BizException;
import com.agent.llm.ChatCompletionRequest;
import com.agent.llm.ChatModelClient;
import com.agent.llm.ChatStreamConsumer;
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
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

@Service
@RequiredArgsConstructor
public class QwenClient implements ChatModelClient {

    private final QwenProperties properties;

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
}
