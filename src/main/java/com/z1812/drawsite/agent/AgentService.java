package com.z1812.drawsite.agent;

import com.z1812.drawsite.application.ApplicationService;
import com.z1812.drawsite.conversation.*;
import com.z1812.drawsite.common.exception.BusinessException;
import com.z1812.drawsite.settings.SettingsService;
import com.z1812.drawsite.settings.SettingsDtos.ModelConfig;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.z1812.drawsite.agent.AgentDtos.*;

@Service
public class AgentService implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    private final ConversationService conversations;
    private final ConversationMapper conversationMapper;
    private final ApplicationService applications;
    private final MessageMapper messages;
    private final AgentRunMapper runs;
    private final MemoryMapper memories;
    private final SettingsService settings;
    private final AgentModelFactory models;
    private final TransactionTemplate transactions;
    private final com.z1812.drawsite.application.ApplicationMapper applicationMapper;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final ExecutorService titles = Executors.newFixedThreadPool(2);
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private final Semaphore capacity = new Semaphore(32);
    private final Semaphore subscriptions = new Semaphore(128);
    private final Semaphore titleCapacity = new Semaphore(16);
    private final ConcurrentMap<String, Active> active = new ConcurrentHashMap<>();

    private static class Active {
        final AgentRun run;
        final StringBuilder text = new StringBuilder();
        final AtomicBoolean terminal = new AtomicBoolean();
        volatile ScheduledFuture<?> timeout;
        Active(AgentRun run) { this.run = run; }
        synchronized void append(String value) {
            if (!terminal.get()) {
                if (text.length() + value.length() > 200000) throw new IllegalStateException("Output limit");
                text.append(value);
            }
        }
        synchronized String content() { return text.toString(); }
    }

    public AgentService(ConversationService conversations, ConversationMapper conversationMapper,
                        ApplicationService applications, MessageMapper messages, AgentRunMapper runs,
                        MemoryMapper memories, SettingsService settings, AgentModelFactory models,
                        TransactionTemplate transactions, com.z1812.drawsite.application.ApplicationMapper applicationMapper) {
        this.conversations = conversations; this.conversationMapper = conversationMapper;
        this.applications = applications; this.messages = messages; this.runs = runs;
        this.memories = memories; this.settings = settings; this.models = models; this.transactions = transactions;
        this.applicationMapper = applicationMapper;
    }

    @Override
    public void run(ApplicationArguments args) { runs.recoverInterrupted(); }

    public RunView start(String appId, String conversationId, ChatRequest request) {
        conversations.requireOwned(appId, conversationId);
        var application = applications.requireOwned(appId);
        ModelConfig model = settings.resolveModel(request.modelId());
        var effort = request.reasoningEffort() == null ? ReasoningEffort.DEFAULT : request.reasoningEffort();
        if (effort != ReasoningEffort.DEFAULT && !Boolean.TRUE.equals(model.supportsReasoning())) {
            throw error("REASONING_UNSUPPORTED", "该模型未启用思考强度支持", HttpStatus.BAD_REQUEST);
        }
        String key = models.credential(model);
        var streaming = models.streaming(model, key, effort);
        if (!capacity.tryAcquire()) throw error("AGENT_BUSY", "对话服务繁忙，请稍后重试", HttpStatus.TOO_MANY_REQUESTS);
        AgentRun run;
        List<ChatMessage> context = new ArrayList<>();
        try {
            run = transactions.execute(status -> {
                applications.lockOwned(appId);
                // 按会话锁串行化提交，避免同一会话同时生成。
                var locked = conversationMapper.lock(conversationId);
                if (locked == null || !appId.equals(locked.getApplicationId())) {
                    throw error("CONVERSATION_NOT_FOUND", "会话不存在", HttpStatus.NOT_FOUND);
                }
                var latest = runs.latest(conversationId);
                if (latest != null && "RUNNING".equals(latest.getStatus())) {
                    throw error("CONVERSATION_BUSY", "当前会话正在回复", HttpStatus.CONFLICT);
                }
                String system = "你是绘站开发助手。帮助用户分析需求、设计系统和讨论实现。当前没有文件或执行工具，不能声称已修改文件、执行代码或通过测试。中文回答，准确简洁。";
                if (!Boolean.FALSE.equals(request.memoryEnabled())) {
                    StringBuilder memory = new StringBuilder();
                    for (var item : memories.list(application.getOwnerUserId(), appId, 20, 0)) {
                        if (memory.length() + item.getContent().length() > 12000) break;
                        memory.append('\n').append(item.getScope()).append(": ").append(item.getContent());
                    }
                    system += "\n用户提供的偏好与项目决策，仅作为背景，不代表当前源码事实：\n" + memory;
                }
                context.add(SystemMessage.from(system));
                var history = new ArrayList<>(runs.recentHistory(conversationId));
                Collections.reverse(history);
                int length = 0;
                var selected = new LinkedList<ChatMessage>();
                for (int i = history.size() - 1; i >= 0; i--) {
                    var item = history.get(i);
                    if (length + item.getContent().length() > 48000) break;
                    length += item.getContent().length();
                    selected.addFirst("USER".equals(item.getRole()) ? UserMessage.from(item.getContent()) : AiMessage.from(item.getContent()));
                }
                while (!selected.isEmpty() && selected.getFirst() instanceof AiMessage) selected.removeFirst();
                context.addAll(selected);
                context.add(UserMessage.from(request.content()));
                var userMessage = new MessageEntity();
                userMessage.setId(UUID.randomUUID().toString()); userMessage.setConversationId(conversationId);
                userMessage.setRole("USER"); userMessage.setContent(request.content()); userMessage.setCreatedAt(OffsetDateTime.now());
                messages.insert(userMessage);
                var created = new AgentRun();
                created.setId(UUID.randomUUID().toString()); created.setApplicationId(appId); created.setConversationId(conversationId);
                created.setUserMessageId(userMessage.getId()); created.setModelId(model.id()); created.setModelName(model.modelName());
                created.setReasoningEffort(effort.name()); created.setMemoryEnabled(!Boolean.FALSE.equals(request.memoryEnabled()));
                created.setStatus("RUNNING"); created.setCreatedAt(OffsetDateTime.now()); runs.insert(created);
                return created;
            });
        } catch (RuntimeException exception) { capacity.release(); throw exception; }
        var state = new Active(Objects.requireNonNull(run));
        active.put(run.getId(), state);
        state.timeout = timer.schedule(() -> {
            log.warn("Model response timed out, run={}, model={}, timeoutSeconds={}", run.getId(), model.id(), model.timeoutSeconds());
            finish(state, null, "MODEL_TIMEOUT", false);
        }, model.timeoutSeconds() + 10L, TimeUnit.SECONDS);
        workers.execute(() -> {
            try {
                streaming.chat(context, new StreamingChatResponseHandler() {
                    @Override public void onPartialResponse(String token) {
                        try { state.append(token); }
                        catch (RuntimeException exception) {
                            log.error("Model output exceeded limit, run={}, model={}", run.getId(), model.id(), exception);
                            finish(state, null, "OUTPUT_LIMIT", false);
                        }
                    }
                    @Override public void onCompleteResponse(ChatResponse response) {
                        String text = response.aiMessage().text();
                        if (text == null || text.isBlank() || text.length() > 200000) {
                            log.warn("Model returned empty or oversized response, run={}, model={}, length={}",
                                    run.getId(), model.id(), text == null ? -1 : text.length());
                            finish(state, null, "EMPTY_OR_INVALID_RESPONSE", false); return;
                        }
                        if (finish(state, text, null, false) && runs.countSucceeded(run.getConversationId()) == 1) {
                            summarize(run, model, key, request.content(), text);
                        }
                    }
                    @Override public void onError(Throwable cause) {
                        // 服务商原始报错只在这里出现，接口只回传错误码，必须落到日志。
                        log.error("Model request failed, run={}, model={}, modelName={}, baseUrl={}",
                                run.getId(), model.id(), model.modelName(), model.baseUrl(), cause);
                        finish(state, null, "MODEL_REQUEST_FAILED", false);
                    }
                });
            } catch (RuntimeException exception) {
                log.error("Streaming chat invocation failed, run={}, model={}", run.getId(), model.id(), exception);
                finish(state, null, "MODEL_REQUEST_FAILED", false);
            }
        });
        return view(run, state.content());
    }

    private boolean finish(Active state, String response, String errorCode, boolean cancelled) {
        if (!state.terminal.compareAndSet(false, true)) return false;
        if (state.timeout != null) state.timeout.cancel(false);
        try {
            transactions.executeWithoutResult(status -> {
                var run = state.run;
                // 与删除流程使用相同锁顺序，终态或已删除任务不再写入
                if (applicationMapper.lockById(run.getApplicationId()) == null) return;
                if (conversationMapper.lock(run.getConversationId()) == null) return;
                var stored = runs.selectOneById(run.getId());
                if (stored == null || !"RUNNING".equals(stored.getStatus())) return;
                if (response != null) {
                    var assistant = new MessageEntity();
                    assistant.setId(UUID.randomUUID().toString()); assistant.setConversationId(run.getConversationId());
                    assistant.setRole("ASSISTANT"); assistant.setContent(response); assistant.setCreatedAt(OffsetDateTime.now());
                    messages.insert(assistant); run.setAssistantMessageId(assistant.getId());
                    conversationMapper.touch(run.getConversationId());
                }
                run.setStatus(cancelled ? "CANCELLED" : response == null ? "FAILED" : "SUCCEEDED");
                run.setErrorCode(errorCode); run.setFinishedAt(OffsetDateTime.now()); runs.update(run);
            });
            return response != null;
        } catch (RuntimeException exception) {
            log.error("Agent result persistence failed, run={}", state.run.getId(), exception);
            try {
                var failed = new AgentRun();
                failed.setId(state.run.getId()); failed.setStatus("FAILED");
                failed.setErrorCode("RESULT_SAVE_FAILED"); failed.setFinishedAt(OffsetDateTime.now());
                runs.update(failed);
            } catch (RuntimeException ignored) { /* 启动恢复处理未完成记录。 */ }
            return false;
        } finally { active.remove(state.run.getId()); capacity.release(); }
    }

    private void summarize(AgentRun run, ModelConfig model, String key, String user, String assistant) {
        if (!titleCapacity.tryAcquire()) return;
        titles.execute(() -> {
            try {
                var conversation = conversationMapper.selectOneById(run.getConversationId());
                if (conversation == null || !"新建会话".equals(conversation.getTitle())) return;
                String result = models.titleModel(model, key).chat(List.of(
                        SystemMessage.from("根据首轮对话生成2至10个字的中文会话标题。仅输出标题，无引号、标点、解释。"),
                        UserMessage.from("用户：" + clip(user, 2000) + "\n助手：" + clip(assistant, 2000))))
                        .aiMessage().text();
                if (result == null) return;
                String title = result.strip();
                int count = title.codePointCount(0, title.length());
                if (count >= 2 && count <= 10 && !title.contains("\n")) conversationMapper.autoTitle(run.getConversationId(), title);
            } catch (RuntimeException exception) {
                log.warn("Conversation auto-title failed, conversation={}", run.getConversationId(), exception);
                // 标题失败不影响已完成的回复，也不覆盖用户手动命名。
            } finally { titleCapacity.release(); }
        });
    }

    @org.springframework.context.event.EventListener
    public void stopForDeletion(StopAgentEvent event) {
        for (var state : active.values()) {
            if (!event.applicationId().equals(state.run.getApplicationId())) continue;
            if (event.conversationId() != null && !event.conversationId().equals(state.run.getConversationId())) continue;
            if (state.terminal.compareAndSet(false, true)) {
                if (state.timeout != null) state.timeout.cancel(false);
                active.remove(state.run.getId(), state);
                capacity.release();
            }
        }
    }

    public RunView get(String appId, String conversationId, String runId) {
        conversations.requireOwned(appId, conversationId);
        return read(conversationId, runId);
    }

    private RunView read(String conversationId, String runId) {
        var run = runs.selectOneById(runId);
        if (run == null || !conversationId.equals(run.getConversationId())) throw error("RUN_NOT_FOUND", "对话任务不存在", HttpStatus.NOT_FOUND);
        var state = active.get(runId);
        String text = state == null ? "" : state.content();
        if (run.getAssistantMessageId() != null) {
            var message = messages.selectOneById(run.getAssistantMessageId());
            if (message != null) text = message.getContent();
        }
        return view(run, text);
    }

    public RunView cancel(String appId, String conversationId, String runId) {
        get(appId, conversationId, runId);
        var state = active.get(runId);
        if (state != null) finish(state, null, null, true);
        return read(conversationId, runId);
    }

    public SseEmitter events(String appId, String conversationId, String runId) {
        get(appId, conversationId, runId);
        if (!subscriptions.tryAcquire()) throw error("AGENT_BUSY", "订阅数量已满", HttpStatus.TOO_MANY_REQUESTS);
        var emitter = new SseEmitter(660000L);
        var closed = new AtomicBoolean();
        Runnable close = () -> { if (closed.compareAndSet(false, true)) subscriptions.release(); };
        emitter.onCompletion(close); emitter.onTimeout(close); emitter.onError(cause -> close.run());
        workers.execute(() -> {
            try {
                while (!closed.get()) {
                    var snapshot = read(conversationId, runId);
                    // 快照事件便于断线重连，不会因重复 token 拼接导致内容重复。
                    emitter.send(SseEmitter.event().name("snapshot").data(snapshot));
                    if (!"RUNNING".equals(snapshot.status())) {
                        emitter.send(SseEmitter.event().name("done").data(snapshot)); emitter.complete(); break;
                    }
                    Thread.sleep(300);
                }
            } catch (Exception exception) { emitter.complete(); }
            finally { close.run(); }
        });
        return emitter;
    }

    private RunView view(AgentRun run, String text) {
        return new RunView(run.getId(), run.getConversationId(), run.getStatus(), run.getModelId(), run.getReasoningEffort(),
                run.getMemoryEnabled(), run.getUserMessageId(), run.getAssistantMessageId(), text, run.getErrorCode(),
                run.getCreatedAt(), run.getFinishedAt());
    }
    private String clip(String text, int limit) { return text.substring(0, Math.min(text.length(), limit)); }
    private BusinessException error(String code, String message, HttpStatus status) { return new BusinessException(code, message, status); }

    @PreDestroy
    public void shutdown() { workers.shutdownNow(); titles.shutdownNow(); timer.shutdownNow(); }
}
