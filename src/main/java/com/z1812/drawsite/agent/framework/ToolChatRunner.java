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
    @org.springframework.beans.factory.annotation.Value("${drawsite.agent.max-tool-rounds:80}")
    private int maxRounds = 80;
    @org.springframework.beans.factory.annotation.Value("${drawsite.agent.max-tool-calls:240}")
    private int maxCalls = 240;
    public interface Observer {
        void waiting(int round);
        void thinking(String text);
        void toolStarted(String name, String arguments);
        void toolFinished(String result);
        void roundFinished(boolean hasTools);
        default void retrying(int round, int retry) { waiting(round); }
    }
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ModelRequestRunner requests = new ModelRequestRunner();
    public static boolean isModelTimeout(Throwable error) { return ModelRequestRunner.isTimeout(error); }

    public void start(StreamingChatModel model, List<ChatMessage> messages, ProjectToolRegistry.Session tools,
                      StreamingChatResponseHandler handler, BooleanSupplier stopped, Observer observer) {
        start(model, messages, tools, handler, stopped, observer, ignored -> {});
    }
    public void start(StreamingChatModel model, List<ChatMessage> messages, ProjectToolRegistry.Session tools,
                      StreamingChatResponseHandler handler, BooleanSupplier stopped, Observer observer,
                      java.util.function.Consumer<List<ChatMessage>> compact) {
        start(model, messages, tools, handler, stopped, observer, compact, 120);
    }
    public void start(StreamingChatModel model, List<ChatMessage> messages, ProjectToolRegistry.Session tools,
                      StreamingChatResponseHandler handler, BooleanSupplier stopped, Observer observer,
                      java.util.function.Consumer<List<ChatMessage>> compact, int timeoutSeconds) {
        next(model, new ArrayList<>(messages), tools, handler, stopped, observer, 0, 0, compact, timeoutSeconds);
    }
    public void startPlain(StreamingChatModel model, List<ChatMessage> messages, StreamingChatResponseHandler handler,
                           BooleanSupplier stopped, Observer observer, int timeoutSeconds) {
        observer.waiting(1);
        requests.chat(model, ChatRequest.builder().messages(messages).build(), handler, stopped, timeoutSeconds, retry -> observer.retrying(1, retry));
    }

    private void next(StreamingChatModel model, List<ChatMessage> messages, ProjectToolRegistry.Session tools,
                        StreamingChatResponseHandler handler, BooleanSupplier stopped, Observer observer, int round, int calls,
                        java.util.function.Consumer<List<ChatMessage>> compact, int timeoutSeconds) {
        if (stopped.getAsBoolean()) return;
        compact.accept(messages);
        if (stopped.getAsBoolean()) return;
        boolean exhausted = round >= maxRounds || calls >= maxCalls;
        if (exhausted) messages.add(UserMessage.from("本次任务已达到工具预算，不能继续调用工具。请使用中文总结已实际完成的工作、未完成项及下一步，让用户可以继续任务。不能把未执行的构建或测试描述为成功。"));
        observer.waiting(round + 1);
        requests.chat(model, ChatRequest.builder().messages(messages).toolSpecifications(exhausted ? List.of() : tools.specifications()).build(),
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
                                messages.add(ai);
                                int executed = 0;
                                for (var call : ai.toolExecutionRequests()) {
                                    if (stopped.getAsBoolean()) return;
                                    if (exhausted || calls + executed >= maxCalls) {
                                        messages.add(ToolExecutionResultMessage.from(call, "本次工具预算已用尽，此调用未执行，请总结进度并等待用户继续"));
                                        continue;
                                    }
                                    observer.toolStarted(call.name(), call.arguments());
                                    String result = tools.execute(call);
                                    executed++;
                                    observer.toolFinished(result);
                                    messages.add(ToolExecutionResultMessage.from(call, result));
                                }
                                if (exhausted) { handler.onError(new IllegalStateException("模型在工具预算用尽后仍请求工具，请继续任务")); return; }
                                next(model, messages, tools, handler, stopped, observer, round + 1, calls + executed, compact, timeoutSeconds);
                            } catch (RuntimeException exception) { handler.onError(exception); }
                        });
                    }
                }, stopped, timeoutSeconds, retry -> observer.retrying(round + 1, retry));
    }

    @PreDestroy
    public void shutdown() { requests.close(); executor.shutdownNow(); }
}
