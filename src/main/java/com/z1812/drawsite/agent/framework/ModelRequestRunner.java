package com.z1812.drawsite.agent.framework;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.*;
import java.util.Locale;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

// 仅重试当前模型请求，不重放已执行的工具
final class ModelRequestRunner implements AutoCloseable {
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    void chat(StreamingChatModel model, ChatRequest request, StreamingChatResponseHandler handler,
              BooleanSupplier stopped, int timeoutSeconds, IntConsumer retry) {
        attempt(model, request, handler, stopped, timeoutSeconds, retry, 0);
    }
    private void attempt(StreamingChatModel model, ChatRequest request, StreamingChatResponseHandler handler,
                         BooleanSupplier stopped, int timeoutSeconds, IntConsumer retry, int retries) {
        if (stopped.getAsBoolean()) return;
        var ended = new AtomicBoolean();
        var guard = new StreamingChatResponseHandler() {
            private volatile ScheduledFuture<?> deadline;
            synchronized void arm() {
                if (deadline != null) deadline.cancel(false);
                if (!ended.get()) deadline = timer.schedule(() -> onError(new TimeoutException("Model response timeout")), timeoutSeconds, TimeUnit.SECONDS);
            }
            void cancel() { if (deadline != null) deadline.cancel(false); }
            @Override public synchronized void onPartialResponse(String text) {
                if (ended.get() || stopped.getAsBoolean()) return;
                arm(); handler.onPartialResponse(text);
            }
            @Override public synchronized void onPartialThinking(PartialThinking thinking) {
                if (ended.get() || stopped.getAsBoolean()) return;
                arm(); handler.onPartialThinking(thinking);
            }
            @Override public synchronized void onCompleteResponse(ChatResponse response) {
                if (!ended.compareAndSet(false, true)) return;
                cancel(); if (!stopped.getAsBoolean()) handler.onCompleteResponse(response);
            }
            @Override public synchronized void onError(Throwable error) {
                if (!ended.compareAndSet(false, true)) return;
                cancel();
                if (stopped.getAsBoolean()) return;
                if (isTimeout(error) && retries < 3) {
                    retry.accept(retries + 1);
                    timer.schedule(() -> workers.execute(() -> attempt(model, request, handler, stopped, timeoutSeconds, retry, retries + 1)), 5, TimeUnit.SECONDS);
                } else workers.execute(() -> { if (!stopped.getAsBoolean()) handler.onError(error); });
            }
        };
        guard.arm();
        try { model.chat(request, guard); }
        catch (RuntimeException error) { guard.onError(error); }
    }
    static boolean isTimeout(Throwable error) {
        for (int i = 0; error != null && i < 12; i++, error = error.getCause()) {
            if (error instanceof TimeoutException || error instanceof java.net.SocketTimeoutException || error instanceof java.net.http.HttpTimeoutException) return true;
            String name = error.getClass().getSimpleName().toLowerCase(Locale.ROOT);
            String message = error.getMessage() == null ? "" : error.getMessage().toLowerCase(Locale.ROOT);
            if (name.contains("timeout") || message.contains("timed out") || message.contains("timeout")) return true;
        }
        return false;
    }
    @Override public void close() { timer.shutdownNow(); workers.shutdownNow(); }
}
