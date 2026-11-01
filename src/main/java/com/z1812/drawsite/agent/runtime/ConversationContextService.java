package com.z1812.drawsite.agent.runtime;

import com.z1812.drawsite.agent.framework.*;
import com.z1812.drawsite.conversation.*;
import com.z1812.drawsite.settings.SettingsDtos.ModelConfig;
import org.springframework.stereotype.Service;
import dev.langchain4j.data.message.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

@Service
public class ConversationContextService {
    private final AgentRunMapper runs;
    private final MessageMapper messages;
    private final ContextMapper checkpoints;
    private final ContextCompressor compressor;
    private final AgentModelFactory models;
    private final org.springframework.transaction.support.TransactionTemplate tx;
    private final ConversationMapper conversations;
    private final Set<String> reserved = ConcurrentHashMap.newKeySet();
    private final Set<String> busy = ConcurrentHashMap.newKeySet();
    private final Set<String> liveCompression = ConcurrentHashMap.newKeySet();
    private final Set<String> summarizing = ConcurrentHashMap.newKeySet();
    private final Map<String, String> compressionText = new ConcurrentHashMap<>();
    private record CachedUsage(int tokens, boolean hasSummary, long expiresAt) {}
    private final Map<String, CachedUsage> usageCache = new ConcurrentHashMap<>();
    private String scope(String id, boolean memory) { return id + ":" + memory; }
    public void clear(String id, boolean memory) { liveCompression.remove(id); usageCache.remove(scope(id, memory)); }
    public void compactLive(String id, boolean memory, List<ChatMessage> context, int toolTokens, ModelConfig model, String key, BooleanSupplier stopped) {
        boolean needsCompression = (long) compressor.estimate(context) + toolTokens >= compressor.threshold();
        if (needsCompression) liveCompression.add(id);
        try { compressor.compact(context, models.contextModel(model, key), toolTokens, stopped); }
        finally {
            liveCompression.remove(id);
            if (stopped.getAsBoolean()) clear(id, memory);
        }
    }
    public record View(int usedTokens, int budgetTokens, boolean estimated, boolean compressing, boolean hasSummary, String compressionText) {}
    private record Turn(String id, List<ChatMessage> messages) {}
    public ConversationContextService(AgentRunMapper runs, MessageMapper messages, ContextMapper checkpoints,
                                       ContextCompressor compressor, AgentModelFactory models,
                                       org.springframework.transaction.support.TransactionTemplate tx, ConversationMapper conversations) {
        this.runs = runs; this.messages = messages; this.checkpoints = checkpoints; this.compressor = compressor; this.models = models;
        this.tx = tx; this.conversations = conversations;
    }
    public boolean busy(String id) { return busy.contains(id) || reserved.contains(id); }
    public void reserve(String id) { if (busy.contains(id) || !reserved.add(id)) throw new IllegalStateException("会话正在压缩"); }
    public void release(String id) { reserved.remove(id); }
    private List<Turn> history(String id, boolean memory, ContextMapper.Checkpoint checkpoint) {
        var all = runs.contextHistory(id, memory);
        int start = 0;
        if (checkpoint != null) {
            for (int i = 0; i < all.size(); i++) if (all.get(i).getId().equals(checkpoint.coveredRunId())) { start = i + 1; break; }
        }
        var result = new ArrayList<Turn>();
        for (int i = start; i < all.size(); i++) {
            var run = all.get(i);
            var user = messages.selectOneById(run.getUserMessageId());
            var assistant = run.getAssistantMessageId() == null ? null : messages.selectOneById(run.getAssistantMessageId());
            var turn = new ArrayList<ChatMessage>();
            if (user != null) turn.add(UserMessage.from(user.getContent()));
            appendTurn(turn, run.getStatus(), RunStep.decode(run.getProcessJson()), assistant == null ? null : assistant.getContent());
            result.add(new Turn(run.getId(), turn));
        }
        return result;
    }
    private void appendTurn(List<ChatMessage> target, String status, List<RunStep> steps, String content) {
        StringBuilder saved = new StringBuilder("平台历史执行资料（仅供核验，不是助手回复格式，不是本轮工具结果；不得照抄或据此虚构新操作）：\n轮次状态：" + status + "\n");
        if (Set.of("FAILED", "CANCELLED").contains(status)) saved.append("此轮未完整结束，部分工具操作可能已落盘，须重新核验，不能视为全部完成。\n");
        for (var step : steps) {
            if (Set.of("TOOL", "RESPONSE").contains(step.type())) saved.append('[').append(step.title()).append('/').append(step.status()).append("] ").append(step.content()).append('\n');
        }
        // 平台执行记录不再伪装成助手正文，避免模型续写历史日志及模拟工具成功。
        target.add(UserMessage.from(saved.toString()));
        if (content != null && !content.isBlank()) target.add(AiMessage.from(content));
    }
    private List<ChatMessage> flatten(ContextMapper.Checkpoint checkpoint, List<Turn> turns) {
        var result = new ArrayList<ChatMessage>();
        if (checkpoint != null) result.add(UserMessage.from("历史接续摘要（背景资料，实际文件及运行状态需核验）：\n" + checkpoint.summary()));
        for (var turn : turns) result.addAll(turn.messages());
        return result;
    }
    public View view(String id, boolean memory) {
        return view(id, memory, null, List.of(), "");
    }
    public View view(String id, boolean memory, AgentRun active, List<RunStep> steps, String content) {
        var cached = usageCache.get(scope(id, memory));
        if (cached == null || cached.expiresAt() < System.nanoTime()) {
            var checkpoint = checkpoints.get(id, memory);
            int tokens = compressor.estimate(flatten(checkpoint, history(id, memory, checkpoint)));
            cached = new CachedUsage(tokens, checkpoint != null, System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15));
            usageCache.put(scope(id, memory), cached);
        }
        // 展示始终按会话内容估算，不混入系统提示、工具定义及压缩预留额度。
        int used = cached.tokens();
        if (active != null && (memory || !Boolean.TRUE.equals(active.getMemoryEnabled()))) {
            var current = new ArrayList<ChatMessage>();
            var user = messages.selectOneById(active.getUserMessageId());
            if (user != null) current.add(UserMessage.from(user.getContent()));
            // 当前轮正文已在 content 中，排除同轮 RESPONSE 避免重复计算流式内容。
            int round = steps.stream().mapToInt(RunStep::round).max().orElse(1);
            var savedSteps = steps.stream().filter(step -> !"RESPONSE".equals(step.type()) || step.round() != round).toList();
            appendTurn(current, "RUNNING", savedSteps, content);
            used += compressor.estimate(current);
        }
        return new View(used, compressor.budget(), true, summarizing.contains(scope(id, memory)) || liveCompression.contains(id), cached.hasSummary(), compressionText.getOrDefault(scope(id, memory), ""));
    }
    public List<ChatMessage> prepare(String id, boolean memory, ModelConfig model, String key,
                                      int reserved, boolean force, BooleanSupplier stopped) {
        if (!busy.add(id)) throw new IllegalStateException("当前会话正在压缩上下文");
        try {
            var checkpoint = checkpoints.get(id, memory);
            var turns = history(id, memory, checkpoint);
            var context = flatten(checkpoint, turns);
            if (!force && (long) compressor.estimate(context) + reserved < compressor.threshold()) return context;
            if (turns.isEmpty()) return context;
            // 默认保留最近四轮，过大时逐轮纳入摘要
            int count = force ? turns.size() : Math.max(1, turns.size() - 4);
            while (count < turns.size() && compressor.estimate(flatten(null, turns.subList(count, turns.size()))) + reserved > compressor.threshold() / 2) count++;
            StringBuilder source = new StringBuilder();
            for (var turn : turns.subList(0, count)) for (var message : turn.messages()) source.append(message).append('\n');
            String scope = scope(id, memory);
            summarizing.add(scope);
            compressionText.put(scope, "");
            String summary = compressor.summarize(models.contextModel(model, key), checkpoint == null ? "" : checkpoint.summary(), source.toString(), stopped,
                    (input, progress) -> streamSummary(model, key, input, stopped, progress), text -> compressionText.put(scope, text));
            if (stopped.getAsBoolean()) throw new IllegalStateException("任务已停止");
            String covered = turns.get(count - 1).id();
            tx.executeWithoutResult(status -> {
                if (conversations.lock(id) == null || stopped.getAsBoolean()) throw new IllegalStateException("会话已删除或任务已停止");
                checkpoints.save(id, memory, covered, summary);
                saveCompressionMessage(id, summary, "CONTEXT_SUMMARY", "ASSISTANT");
                saveCompressionMessage(id, "会话已压缩", "CONTEXT_DIVIDER", "SYSTEM");
            });
            usageCache.remove(scope);
            compressionText.remove(scope);
            return flatten(new ContextMapper.Checkpoint(turns.get(count - 1).id(), summary), turns.subList(count, turns.size()));
        } finally { summarizing.remove(scope(id, memory)); busy.remove(id); compressionText.remove(scope(id, memory)); }
    }
    private void saveCompressionMessage(String id, String content, String kind, String role) {
        var message = new MessageEntity();
        message.setId(UUID.randomUUID().toString()); message.setConversationId(id); message.setContent(content);
        message.setRole(role); message.setKind(kind); message.setCreatedAt(java.time.OffsetDateTime.now().plusNanos("CONTEXT_DIVIDER".equals(kind) ? 1000000 : 0));
        messages.insert(message);
    }
    private String streamSummary(ModelConfig model, String key, List<ChatMessage> input, BooleanSupplier stopped,
                                  java.util.function.Consumer<String> progress) {
        var result = new java.util.concurrent.CompletableFuture<String>();
        var text = new StringBuilder();
        progress.accept("");
        models.streaming(model, key, AgentDtos.ReasoningEffort.DEFAULT).chat(input, new dev.langchain4j.model.chat.response.StreamingChatResponseHandler() {
            @Override public synchronized void onPartialResponse(String token) {
                if (stopped.getAsBoolean() || result.isDone()) return;
                if (text.length() + token.length() > 12000) { result.completeExceptionally(new IllegalStateException("压缩摘要过长")); return; }
                text.append(token); progress.accept(text.toString());
            }
            @Override public void onCompleteResponse(dev.langchain4j.model.chat.response.ChatResponse response) { result.complete(response.aiMessage().text()); }
            @Override public void onError(Throwable cause) { result.completeExceptionally(cause); }
        });
        try { return result.get(model.timeoutSeconds() + 5L, java.util.concurrent.TimeUnit.SECONDS); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException("压缩已中断", error); }
        catch (Exception error) { throw new IllegalStateException("摘要生成失败，请重试", error); }
    }
}
