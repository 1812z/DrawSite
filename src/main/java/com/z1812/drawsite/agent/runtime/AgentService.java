package com.z1812.drawsite.agent.runtime;

import com.z1812.drawsite.agent.framework.AgentModelFactory;
import com.z1812.drawsite.agent.framework.ToolChatRunner;
import com.z1812.drawsite.agent.memory.MemoryMapper;
import com.z1812.drawsite.application.ApplicationMapper;
import com.z1812.drawsite.tool.framework.ProjectToolRegistry;
import com.z1812.drawsite.tool.framework.ToolContext;

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
import static com.z1812.drawsite.agent.runtime.AgentDtos.*;

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
    private final ApplicationMapper applicationMapper;
    private final ProjectToolRegistry toolRegistry;
    private final ToolChatRunner toolRunner;
    private final com.z1812.drawsite.agent.clarification.ClarificationService clarifications;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final ExecutorService titles = Executors.newFixedThreadPool(2);
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private final Semaphore capacity = new Semaphore(32);
    private final Semaphore subscriptions = new Semaphore(128);
    private final Semaphore titleCapacity = new Semaphore(16);
    private final ConcurrentMap<String, Active> active = new ConcurrentHashMap<>();

    private static class Active implements ToolChatRunner.Observer {
        final AgentRun run;
        final StringBuilder text = new StringBuilder();
        final AtomicBoolean terminal = new AtomicBoolean();
        final List<RunStep> steps = new ArrayList<>();
        String phase = "WAITING_MODEL";
        int round = 1;
        int processLength;
        volatile ScheduledFuture<?> timeout;
        long timeoutSeconds;
        Active(AgentRun run) { this.run = run; }
        synchronized void append(String value) {
            if (!terminal.get()) {
                if (text.length() + value.length() > 200000) throw new IllegalStateException("Output limit");
                text.append(value);
                phase = "RESPONDING";
                appendStep("RESPONSE", "生成回复", value, null);
            }
        }
        synchronized String content() { return text.toString(); }
        synchronized List<RunStep> snapshot() { return List.copyOf(steps); }
        synchronized String phase() { return phase; }
        private void endStep(String status) {
            if (steps.isEmpty()) return;
            var last = steps.getLast();
            if (!"RUNNING".equals(last.status())) return;
            steps.set(steps.size() - 1, new RunStep(last.id(), last.round(), last.type(), last.title(), status,
                    last.content(), last.toolName(), last.startedAt(), OffsetDateTime.now()));
        }
        private void appendStep(String type, String title, String value, String toolName) {
            if (processLength + value.length() > 1000000) throw new IllegalStateException("Process output limit");
            processLength += value.length();
            if (!steps.isEmpty() && steps.getLast().type().equals(type) && "RUNNING".equals(steps.getLast().status())) {
                var last = steps.getLast();
                steps.set(steps.size() - 1, new RunStep(last.id(), round, type, title, "RUNNING", last.content() + value,
                        toolName, last.startedAt(), null));
            } else {
                endStep("SUCCEEDED");
                steps.add(new RunStep(UUID.randomUUID().toString(), round, type, title, "RUNNING", value, toolName, OffsetDateTime.now(), null));
            }
        }
        @Override public synchronized void waiting(int round) {
            if (terminal.get()) return;
            this.round = round;
            text.setLength(0);
            phase = "WAITING_MODEL";
            appendStep("WAITING_MODEL", "等待模型响应", "", null);
        }
        @Override public synchronized void thinking(String value) {
            if (terminal.get()) return;
            phase = "THINKING";
            appendStep("THINKING", "思考中", value, null);
        }
        @Override public synchronized void roundFinished(boolean hasTools) {
            if (terminal.get()) return;
            endStep("SUCCEEDED");
            if (hasTools) text.setLength(0);
            else steps.removeIf(step -> step.round() == round && step.type().equals("RESPONSE"));
        }
        @Override public synchronized void toolStarted(String name, String arguments) {
            if (terminal.get()) return;
            phase = "CALLING_TOOL";
            String title = switch (name) {
                case "listProjectFiles" -> "查看目录";
                case "readProjectFile" -> "读取文件";
                case "searchProjectCode" -> "搜索文件";
                case "writeProjectFile" -> "写入文件";
                case "applyProjectPatch" -> "应用补丁";
                case "replaceProjectText" -> "替换文本";
                case "deleteProjectFile" -> "删除文件";
                case "moveProjectFile" -> "移动文件";
                case "createProjectDirectory" -> "创建目录";
                case "askDetails" -> "询问细节";
                case "readGlobalMemory" -> "读取全局记忆";
                case "writeGlobalMemory" -> "更新全局记忆";
                case "readProgress" -> "查看开发进度";
                case "writeProgress" -> "更新开发进度";
                case "httpRequest" -> "网络请求";
                case "downloadResource" -> "下载资源";
                default -> name;
            };
            appendStep("TOOL", "调用 " + title, clipDetail(arguments), name);
        }
        @Override public synchronized void toolFinished(String result) {
            if (terminal.get()) return;
            var last = steps.getLast();
            String detail = last.content() + "\n\n" + clipDetail(result);
            steps.set(steps.size() - 1, new RunStep(last.id(), round, last.type(), last.title(),
                    result.contains("\"code\":\"OK\"") ? "SUCCEEDED" : "FAILED", detail, last.toolName(), last.startedAt(), OffsetDateTime.now()));
        }
        synchronized void ended(String status) { endStep(status); phase = status; }
        private static String clipDetail(String value) { return value == null ? "" : value.substring(0, Math.min(value.length(), 20000)); }
    }

    public AgentService(ConversationService conversations, ConversationMapper conversationMapper,
                        ApplicationService applications, MessageMapper messages, AgentRunMapper runs,
                        MemoryMapper memories, SettingsService settings, AgentModelFactory models,
                        TransactionTemplate transactions, ApplicationMapper applicationMapper,
                         ProjectToolRegistry toolRegistry, ToolChatRunner toolRunner,
                         com.z1812.drawsite.agent.clarification.ClarificationService clarifications) {
        this.conversations = conversations; this.conversationMapper = conversationMapper;
        this.applications = applications; this.messages = messages; this.runs = runs;
        this.memories = memories; this.settings = settings; this.models = models; this.transactions = transactions;
        this.applicationMapper = applicationMapper;
        this.toolRegistry = toolRegistry; this.toolRunner = toolRunner;
        this.clarifications = clarifications;
    }

    @Override
    public void run(ApplicationArguments args) { runs.recoverInterrupted(); clarifications.recover(); }

    public RunView start(String appId, String conversationId, ChatRequest request) {
        conversations.requireOwned(appId, conversationId);
        var application = applications.requireOwned(appId);
        ModelConfig model = settings.resolveModel(request.modelId());
        Set<String> selectedTools = toolRegistry.select(request.toolNames());
        boolean toolsEnabled = Boolean.TRUE.equals(model.supportsTools());
        if (!toolsEnabled && (Boolean.TRUE.equals(request.toolsEnabled()) || (request.toolNames() != null && !request.toolNames().isEmpty()))) {
            throw error("TOOLS_UNSUPPORTED", "该模型未启用工具支持", HttpStatus.BAD_REQUEST);
        }
        if (Boolean.FALSE.equals(request.toolsEnabled())) selectedTools = Set.of();
        final Set<String> enabledTools = selectedTools;
        if (toolsEnabled && !Boolean.TRUE.equals(model.supportsTools())) {
            throw error("TOOLS_UNSUPPORTED", "该模型未启用工具支持", HttpStatus.BAD_REQUEST);
        }
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
                // 会话锁避免重复生成
                var locked = conversationMapper.lock(conversationId);
                if (locked == null || !appId.equals(locked.getApplicationId())) {
                    throw error("CONVERSATION_NOT_FOUND", "会话不存在", HttpStatus.NOT_FOUND);
                }
                var latest = runs.latest(conversationId);
                if (latest != null && "RUNNING".equals(latest.getStatus())) {
                    throw error("CONVERSATION_BUSY", "当前会话正在回复", HttpStatus.CONFLICT);
                }
                String system = "你是绘站开发助手。帮助用户分析需求、设计系统和讨论实现。当前没有文件或执行工具，不能声称已修改文件、执行代码或通过测试。中文回答，准确简洁。";
                if (toolsEnabled) system = "你是绘站开发助手，按提供的工具操作当前项目。若开放文件工具，先读取相关需求/设计，修改前读取sha256；局部修改优先applyProjectPatch或replaceProjectText。关键需求不清晰时可用askDetails一次询问1至3个问题，收到用户答案再继续。只能操作绑定项目，不访问内部目录。不能声称已经构建或通过测试，当前没有执行工具。每次写入实际落盘，取消不会回滚已完成修改。中文回答，注释简短、单行、结尾无句号。";
                if (toolsEnabled) system += "\n用readProgress和writeProgress维护progress.json，开始前查看已有进度，完成实现后标READY_FOR_VERIFICATION，不能把未经平台验证的结果标为测试通过。";
                if (!Boolean.FALSE.equals(request.memoryEnabled())) {
                    var memory = memories.get(application.getOwnerUserId());
                    if (memory != null) system += "\n用户全局偏好，仅作为背景，不代表当前源码事实：\n" + memory.content();
                    if (toolsEnabled) system += "\n项目记忆为AGENTS.md。开始任务时通过文件工具读取，记录已确认的项目偏好与决策时可修改此文件，不写入密钥、对话流水或未经验证的结果。";
                    if (toolsEnabled) system += "\n若开放全局记忆工具，可先readGlobalMemory，再writeGlobalMemory更新用户明确表达的跨项目偏好，保留仍有效的内容。项目专属信息写项目记忆，不写全局记忆。";
                }
                context.add(SystemMessage.from(system));
                String clarificationHistory = clarifications.history(conversationId);
                if (!clarificationHistory.isEmpty()) context.add(UserMessage.from("当前会话之前已确认的需求细节，作为用户背景资料：" + clarificationHistory));
                var history = new ArrayList<>(runs.recentHistory(conversationId, !Boolean.FALSE.equals(request.memoryEnabled())));
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
        state.timeoutSeconds = model.timeoutSeconds() + 10L;
        scheduleTimeout(state);
        workers.execute(() -> {
            try {
                var handler = new StreamingChatResponseHandler() {
                    @Override public void onPartialThinking(dev.langchain4j.model.chat.response.PartialThinking thinking) {
                        try { state.thinking(thinking.text()); }
                        catch (RuntimeException exception) { finish(state, null, "OUTPUT_LIMIT", false); }
                    }
                    @Override public void onPartialResponse(String token) {
                        try { state.append(token); }
                        catch (RuntimeException exception) {
                            log.error("Model output exceeded limit, run={}, model={}", run.getId(), model.id(), exception);
                            finish(state, null, "OUTPUT_LIMIT", false);
                        }
                    }
                    @Override public void onCompleteResponse(ChatResponse response) {
                        state.roundFinished(false);
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
                        // 原始错误写日志，接口仅返回错误码
                        log.error("Model request failed, run={}, model={}, modelName={}, baseUrl={}",
                                run.getId(), model.id(), model.modelName(), model.baseUrl(), cause);
                        finish(state, null, "MODEL_REQUEST_FAILED", false);
                    }
                };
                if (toolsEnabled) {
                    var toolContext = new ToolContext(appId, application.getOwnerUserId(), run.getId(), state.terminal::get,
                            waiting -> waitForUser(state, waiting), run.getMemoryEnabled());
                    toolRunner.start(streaming, context, toolRegistry.bind(toolContext, enabledTools), handler, state.terminal::get, state);
                } else { state.waiting(1); streaming.chat(context, handler); }
            } catch (RuntimeException exception) {
                log.error("Streaming chat invocation failed, run={}, model={}", run.getId(), model.id(), exception);
                finish(state, null, "MODEL_REQUEST_FAILED", false);
            }
        });
        return view(run, state.content());
    }

    private boolean finish(Active state, String response, String errorCode, boolean cancelled) {
        if (!state.terminal.compareAndSet(false, true)) return false;
        state.ended(cancelled ? "CANCELLED" : response == null ? "FAILED" : "SUCCEEDED");
        state.run.setProcessJson(RunStep.encode(state.snapshot()));
        if (state.timeout != null) state.timeout.cancel(false);
        try {
            transactions.executeWithoutResult(status -> {
                var run = state.run;
                // 与删除流程使用相同锁顺序，终态或已删除任务不再写入
                if (applicationMapper.lockById(run.getApplicationId()) == null) return;
                if (conversationMapper.lock(run.getConversationId()) == null) return;
                var stored = runs.selectOneById(run.getId());
                if (stored == null || !"RUNNING".equals(stored.getStatus())) return;
                clarifications.cancel(run.getId());
                if (response != null) {
                    var assistant = new MessageEntity();
                    assistant.setId(UUID.randomUUID().toString()); assistant.setConversationId(run.getConversationId());
                    assistant.setRole("ASSISTANT"); assistant.setContent(response); assistant.setCreatedAt(OffsetDateTime.now());
                    assistant.setProcessJson(run.getProcessJson());
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
            } catch (RuntimeException ignored) { /* 启动时恢复未完成任务 */ }
            return false;
        } finally { active.remove(state.run.getId()); capacity.release(); }
    }

    private void scheduleTimeout(Active state) {
        state.timeout = timer.schedule(() -> {
            synchronized (state) {
                if ("WAITING_USER".equals(state.phase) || state.terminal.get()) return;
                finish(state, null, "MODEL_TIMEOUT", false);
            }
        }, state.timeoutSeconds, TimeUnit.SECONDS);
    }

    private void waitForUser(Active state, boolean waiting) {
        synchronized (state) {
            if (state.terminal.get()) return;
            if (waiting) {
                if (state.timeout != null) state.timeout.cancel(false);
                state.phase = "WAITING_USER";
                state.run.setProcessJson(RunStep.encode(state.snapshot()));
                runs.saveProcess(state.run.getId(), state.run.getProcessJson());
            } else {
                state.phase = "CALLING_TOOL";
                scheduleTimeout(state);
            }
        }
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
                // 标题失败不影响回复
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
                    // 全量快照避免重连后重复拼接
                    emitter.send(SseEmitter.event().name("snapshot").data(snapshot));
                    if (!"RUNNING".equals(snapshot.status())) {
                        emitter.send(SseEmitter.event().name("done").data(snapshot));
                        if (!closed.get()) emitter.complete();
                        break;
                    }
                    Thread.sleep(300);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (Exception exception) {
                // 发送失败后不再完成失效响应
                log.debug("SSE subscription ended, run={}", runId);
            }
            finally { close.run(); }
        });
        return emitter;
    }

    private RunView view(AgentRun run, String text) {
        var state = active.get(run.getId());
        return new RunView(run.getId(), run.getConversationId(), run.getStatus(), run.getModelId(), run.getReasoningEffort(),
                run.getMemoryEnabled(), run.getUserMessageId(), run.getAssistantMessageId(), text, run.getErrorCode(),
                run.getCreatedAt(), run.getFinishedAt(),
                state == null ? run.getStatus() : state.phase(),
                state == null ? RunStep.decode(run.getProcessJson()) : state.snapshot(), clarifications.pending(run.getId()));
    }
    private String clip(String text, int limit) { return text.substring(0, Math.min(text.length(), limit)); }
    private BusinessException error(String code, String message, HttpStatus status) { return new BusinessException(code, message, status); }

    @PreDestroy
    public void shutdown() { workers.shutdownNow(); titles.shutdownNow(); timer.shutdownNow(); }
}
