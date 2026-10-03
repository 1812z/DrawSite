package com.z1812.drawsite.workspace;

import com.z1812.drawsite.tool.framework.ToolContext;
import com.z1812.drawsite.tool.framework.ToolGuard;
import org.springframework.stereotype.Service;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.nio.charset.StandardCharsets;

@Service
public class NetworkService {
    private final ProjectFileService files;
    private final ToolGuard guard;
    public NetworkService(ProjectFileService files, ToolGuard guard) { this.files = files; this.guard = guard; }
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private static final int LIMIT = 2 * 1024 * 1024;

    public Object execute(String function, Map<String, Object> args, ToolContext context) throws Exception {
        if (!Set.of("httpRequest", "downloadResource").contains(function)) throw new IllegalArgumentException("未知网络函数");
        if (context.stopped().getAsBoolean()) throw new IllegalStateException("任务已取消");
        URI uri = URI.create(string(args, "url", null));
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null) throw new IllegalArgumentException("需要有效的HTTP或HTTPS URL，认证请使用请求头");
        String method = function.equals("downloadResource") ? "GET" : string(args, "method", "GET").toUpperCase(Locale.ROOT);
        if (!Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS").contains(method)) throw new IllegalArgumentException("不支持该HTTP方法");
        String body = string(args, "body", "");
        if (body.getBytes(StandardCharsets.UTF_8).length > LIMIT) throw new IllegalArgumentException("请求体超过2MB");
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30));
        if (args.get("headers") instanceof Map<?, ?> headers) {
            if (headers.size() > 50) throw new IllegalArgumentException("请求头最多50项");
            for (var entry : headers.entrySet()) {
                if (!(entry.getKey() instanceof String key) || !(entry.getValue() instanceof String value)) throw new IllegalArgumentException("请求头必须是字符串键值");
                request.header(key, value);
            }
        }
        request.method(method, body.isEmpty() ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        var handler = HttpResponse.BodyHandlers.ofByteArray();
        var future = client.sendAsync(request.build(), info -> new HttpResponse.BodySubscriber<byte[]>() {
            private final HttpResponse.BodySubscriber<byte[]> delegate = handler.apply(info);
            private java.util.concurrent.Flow.Subscription subscription;
            private int bytes;
            public java.util.concurrent.CompletionStage<byte[]> getBody() { return delegate.getBody(); }
            public void onSubscribe(java.util.concurrent.Flow.Subscription value) { subscription = value; delegate.onSubscribe(value); }
            public void onNext(List<java.nio.ByteBuffer> buffers) {
                for (var buffer : buffers) bytes += buffer.remaining();
                if (bytes > LIMIT || context.stopped().getAsBoolean()) { subscription.cancel(); delegate.onError(new IllegalArgumentException("响应超过2MB或任务已取消")); }
                else delegate.onNext(buffers);
            }
            public void onError(Throwable error) { delegate.onError(error); }
            public void onComplete() { delegate.onComplete(); }
        });
        HttpResponse<byte[]> response;
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        try {
            while (true) {
                if (context.stopped().getAsBoolean()) throw new IllegalStateException("任务已取消");
                if (System.nanoTime() >= deadline) throw new IllegalArgumentException("网络请求超过30秒");
                try { response = future.get(200, java.util.concurrent.TimeUnit.MILLISECONDS); break; }
                catch (java.util.concurrent.TimeoutException ignored) { }
            }
        } finally { if (!future.isDone()) future.cancel(true); }
        if (function.equals("downloadResource")) {
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IllegalArgumentException("下载HTTP状态码: " + response.statusCode());
            byte[] bytes = response.body();
            return guard.execute(context, () -> files.writeBytes(context.applicationId(), string(args, "path", null), bytes, string(args, "expectedHash", null)));
        }
        String text = new String(response.body(), StandardCharsets.UTF_8);
        return Map.of("status", response.statusCode(), "headers", response.headers().map(), "body", text.substring(0, Math.min(text.length(), 40000)), "truncated", text.length() > 40000);
    }
    private static String string(Map<String, Object> args, String name, String fallback) {
        Object value = args.get(name);
        if (value == null && fallback != null) return fallback;
        if (!(value instanceof String text) || (fallback == null && text.isBlank())) throw new IllegalArgumentException("参数不合法: " + name);
        return text;
    }
    @PreDestroy public void close() { client.close(); }
}
