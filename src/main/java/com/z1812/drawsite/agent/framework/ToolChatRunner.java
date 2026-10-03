package com.z1812.drawsite.agent.framework;

import com.z1812.drawsite.tool.framework.ProjectToolRegistry;

import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.*;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

@Component
public class ToolChatRunner {
    public interface Observer {
        void waiting(int round);
        void thinking(String text);
        void toolStarted(String name, String arguments);
        void toolFinished(String result);
        void roundFinished(boolean hasTools);
    }
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public void start(StreamingChatModel model, List<ChatMessage> messages, ProjectToolRegistry.Session tools,
                      StreamingChatResponseHandler handler, BooleanSupplier stopped, Observer observer) {
        next(model, new ArrayList<>(messages), tools, handler, stopped, observer, 0, 0);
    }

    private void next(StreamingChatModel model, List<ChatMessage> messages, ProjectToolRegistry.Session tools,
                       StreamingChatResponseHandler handler, BooleanSupplier stopped, Observer observer, int round, int calls) {
        if (stopped.getAsBoolean()) return;
        observer.waiting(round + 1);
        model.chat(ChatRequest.builder().messages(messages).toolSpecifications(tools.specifications()).build(),
                new StreamingChatResponseHandler() {
                    @Override public void onPartialResponse(String text) { if (!stopped.getAsBoolean()) handler.onPartialResponse(text); }
                    @Override public void onPartialThinking(PartialThinking thinking) { if (!stopped.getAsBoolean()) handler.onPartialThinking(thinking); }
                    @Override public void onError(Throwable cause) { if (!stopped.getAsBoolean()) handler.onError(cause); }
                    @Override public void onCompleteResponse(ChatResponse response) {
                        if (stopped.getAsBoolean()) return;
                        var ai = response.aiMessage();
                        observer.roundFinished(ai.hasToolExecutionRequests());
                        if (!ai.hasToolExecutionRequests()) { handler.onCompleteResponse(response); return; }
                        executor.execute(() -> {
                            try {
                                if (round >= 12 || calls + ai.toolExecutionRequests().size() > 40) throw new IllegalStateException("Tool call limit");
                                messages.add(ai);
                                for (var call : ai.toolExecutionRequests()) {
                                    if (stopped.getAsBoolean()) return;
                                    observer.toolStarted(call.name(), call.arguments());
                                    String result = tools.execute(call);
                                    observer.toolFinished(result);
                                    messages.add(ToolExecutionResultMessage.from(call, result));
                                }
                                next(model, messages, tools, handler, stopped, observer, round + 1, calls + ai.toolExecutionRequests().size());
                            } catch (RuntimeException exception) { handler.onError(exception); }
                        });
                    }
                });
    }

    @PreDestroy
    public void shutdown() { executor.shutdownNow(); }
}
