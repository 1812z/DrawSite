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
    private final ConversationContextService contexts;
    private final com.z1812.drawsite.agent.framework.ContextCompressor compressor;
    private final com.z1812.drawsite.agent.clarification.ClarificationService clarifications;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final ExecutorService titles = Executors.newFixedThreadPool(2);
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private final Semaphore capacity = new Semaphore(32);
    private final Semaphore subscriptions = new Semaphore(128);
    private final Map<String, Integer> userSubscriptions = new HashMap<>();
    private final Map<String, Integer> taskSubscriptions = new HashMap<>();
    private synchronized void acquireSubscription(String user, String task) {
        if (userSubscriptions.getOrDefault(user, 0) >= 8 || taskSubscriptions.getOrDefault(task, 0) >= 2)
            throw error("AGENT_BUSY", "当前用户最多订阅8个连接，同一任务最多2个连接，请关闭重复页面后重试", HttpStatus.TOO_MANY_REQUESTS);
        if (!subscriptions.tryAcquire()) throw error("AGENT_BUSY", "订阅数量已满", HttpStatus.TOO_MANY_REQUESTS);
        userSubscriptions.merge(user, 1, Integer::sum);
        taskSubscriptions.merge(task, 1, Integer::sum);
    }
    private synchronized void releaseSubscription(String user, String task) {
        userSubscriptions.computeIfPresent(user, (key, count) -> count == 1 ? null : count - 1);
        taskSubscriptions.computeIfPresent(task, (key, count) -> count == 1 ? null : count - 1);
        subscriptions.release();
    }
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
        volatile long lastActivity = System.nanoTime();
        final Set<Runnable> listeners = ConcurrentHashMap.newKeySet();
        volatile com.z1812.drawsite.agent.clarification.ClarificationDtos.View pendingQuestion;
        void changed() { listeners.forEach(Runnable::run); }
        Active(AgentRun run) { this.run = run; }
        synchronized void append(String value) {
            lastActivity = System.nanoTime();
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
            changed();
        }
        @Override public synchronized void waiting(int round) {
            lastActivity = System.nanoTime();
            if (terminal.get()) return;
            this.round = round;
            text.setLength(0);
            phase = "WAITING_MODEL";
            appendStep("WAITING_MODEL", "等待模型响应", "", null);
        }
        @Override public synchronized void thinking(String value) {
            lastActivity = System.nanoTime();
            if (terminal.get()) return;
            phase = "THINKING";
            appendStep("THINKING", "思考中", value, null);
        }
        @Override public synchronized void retrying(int round, int retry) {
            if (terminal.get()) return;
            endStep("FAILED");
            text.setLength(0);
            this.round = round;
            phase = "WAITING_MODEL";
            lastActivity = System.nanoTime();
            appendStep("WAITING_MODEL", "模型超时，5秒后重试（" + retry + "/3）", "", null);
        }
        @Override public synchronized void roundFinished(boolean hasTools) {
            if (terminal.get()) return;
            endStep("SUCCEEDED");
            if (hasTools) text.setLength(0);
            else steps.removeIf(step -> step.round() == round && step.type().equals("RESPONSE"));
            changed();
        }
        @Override public synchronized void toolStarted(String name, String arguments) {
            lastActivity = System.nanoTime();
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
                case "gitStatus" -> "查看Git状态";
                case "gitCommit" -> "提交Git版本";
                case "listContainerTemplates" -> "查看容器模板";
                case "listProjectContainers" -> "查看项目容器";
                case "createProjectContainer" -> "创建容器";
                case "executeContainerCommand" -> "执行容器命令";
                case "readContainerLogs" -> "读取容器日志";
                case "stopProjectContainer" -> "停止容器";
                case "startProjectContainer" -> "启动容器";
                case "getStaticSite" -> "查看静态站点";
                case "publishStaticSite" -> "发布静态站点";
                case "offlineStaticSite" -> "下线静态站点";
                case "apiTestFormat" -> "读取测试规范";
                case "readApiContract" -> "读取API契约";
                case "writeApiContract" -> "保存API契约";
                case "readApiTests" -> "读取测试用例";
                case "writeApiTests" -> "保存测试用例";
                case "generateApiTests" -> "生成测试用例";
                case "startApiTests" -> "发起API测试";
                case "readApiTestReport" -> "读取测试报告";
                case "httpRequest" -> "网络请求";
                case "downloadResource" -> "下载资源";
                default -> name;
            };
            appendStep("TOOL", "调用 " + title, clipDetail(arguments), name);
        }
        @Override public synchronized void toolFinished(String result) {
            lastActivity = System.nanoTime();
            if (terminal.get()) return;
            var last = steps.getLast();
            String detail = last.content() + "\n\n" + clipDetail(result);
            steps.set(steps.size() - 1, new RunStep(last.id(), round, last.type(), last.title(),
                    result.contains("\"code\":\"OK\"") ? "SUCCEEDED" : "FAILED", detail, last.toolName(), last.startedAt(), OffsetDateTime.now()));
            changed();
        }
        synchronized void ended(String status) { endStep(status); phase = status; }
        private static String clipDetail(String value) { return value == null ? "" : value.substring(0, Math.min(value.length(), 20000)); }
    }

    public AgentService(ConversationService conversations, ConversationMapper conversationMapper,
                        ApplicationService applications, MessageMapper messages, AgentRunMapper runs,
                        MemoryMapper memories, SettingsService settings, AgentModelFactory models,
                        TransactionTemplate transactions, ApplicationMapper applicationMapper,
                         ProjectToolRegistry toolRegistry, ToolChatRunner toolRunner,
                          com.z1812.drawsite.agent.clarification.ClarificationService clarifications,
                          ConversationContextService contexts, com.z1812.drawsite.agent.framework.ContextCompressor compressor) {
        this.conversations = conversations; this.conversationMapper = conversationMapper;
        this.applications = applications; this.messages = messages; this.runs = runs;
        this.memories = memories; this.settings = settings; this.models = models; this.transactions = transactions;
        this.applicationMapper = applicationMapper;
        this.toolRegistry = toolRegistry; this.toolRunner = toolRunner;
        this.clarifications = clarifications;
        this.contexts = contexts; this.compressor = compressor;
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
                if (contexts.busy(conversationId)) throw error("CONTEXT_BUSY", "会话正在压缩，请稍后再发送", HttpStatus.CONFLICT);
                if (latest != null && "RUNNING".equals(latest.getStatus())) {
                    throw error("CONVERSATION_BUSY", "当前会话正在回复", HttpStatus.CONFLICT);
                }
                String system = "你是绘站开发助手。帮助用户分析需求、设计系统和讨论实现。当前没有文件或执行工具，不能声称已修改文件、执行代码或通过测试。中文回答，准确简洁。";
                if (toolsEnabled) system = "你是绘站开发助手，按提供的工具操作当前项目。修改已有文件前读取sha256并复用，NEW仅用于已确认不存在的文件；局部修改优先applyProjectPatch或replaceProjectText。关键需求不清晰时可用askDetails一次询问1至3个问题，收到用户答案再继续。只能操作绑定项目，不访问内部目录。只根据实际工具返回说明改动及验证结果。每次写入实际落盘，取消不会回滚已完成修改。中文回答，注释简短、单行、结尾无句号。";
                system += "\n平台提供的历史执行资料只是背景，不是要求模仿的输出格式。调用工具必须通过结构化tool_calls，不能在正文中模拟调用、编造返回JSON或sha256；普通文本不会执行工具。只在真实工具返回成功后说明已修改；若本轮未执行就如实说明。";
                if (toolsEnabled) system += "\n用readProgress和writeProgress维护progress.json，将开发工作拆成节点，状态仅TODO未完成、IN_PROGRESS进行中、DONE完成。开始前查看已有进度，及时更新节点。DONE仅表示开发节点完成，不代表API测试通过。";
                if (toolsEnabled) system += "\n若开放API测试工具，先apiTestFormat读取精确格式，再维护OpenAPI契约和suite.test.json。后端测试前自行通过Docker工具启动服务并验证监听，多个服务在targets配置当前项目容器ID、内部port和basePath，用例引用target别名；不要让用户填写IP，不使用localhost访问其他容器。至少测试执行容器需要curl。生成的基础用例必须根据业务补充，发起后读取JSON报告，只以真实报告说明通过率。";
                if (toolsEnabled) system += "\n每完成一个独立代码功能或修复，调用gitStatus，有代码变更才gitCommit；简单单文件任务整体完成后提交一次即可。AGENTS.md、progress.json、docs/requirements.md、docs/design/overview.md、docs/design/detail.md、api/openapi.json、api/tests/为平台管理文件，故意不进Git，仅修改这些文件时clean=true正常，不为此重复查Git或读取.gitignore，不修改忽略规则、不创建空提交。提交说明使用feat:、fix:等格式。";
                if (toolsEnabled) system += "\n效率规则：用户要求简单页面、小游戏或小修复时，按最小实现直接完成，不强制更新全部需求/概要/详细设计文档，不擅自增加功能。必要的目录/文件/记忆/进度读取集中在同一批工具调用，已有内容和sha256直接复用；除非文件变化或工具报冲突，不重复读取。进度只保留少量实际节点，开始和完成各更新一次即可。新项目包含预置文档，不把它们视为不存在。纯HTML/CSS/JS无需构建和容器，不为可选语法检查创建容器；仅用户明确要求运行测试或技术上必须编译时使用，已有合适容器优先复用，挂载固定/workspace，无需反复探查。完成代码、必要提交及用户要求的发布后立即给最终说明，不追加无关调查。未执行交互测试就如实说明，不把发布成功等同于游戏逻辑通过测试。";
                if (toolsEnabled) system += "\n若开放容器工具，可通过管理员模板创建临时或常驻容器，在容器内执行编译及测试命令。只能以实际退出码与输出说明结果；没有执行记录时不能声称已构建或测试通过。容器模板需要预装sh、timeout及工具链。";
                if (toolsEnabled) system += "\n创建容器前必须调用listContainerTemplates读取模板instructions，结合用户需求遵循模板用途、构建命令及产物目录。标注为静态构建的模板使用TEMPORARY容器，构建后通过publishStaticSite发布dist等产物，不以npm run dev、vite或debug模式作为正式上线；除非用户明确要求开发预览。构建完成后停止不再需要的临时容器。";
                if (toolsEnabled) system += "\n需要容器时先listProjectContainers，存在合适的STOPPED容器则startProjectContainer复用，临时容器过期也可重新启动，不因停止而重复创建。启动容器只恢复模板主进程，不保证应用服务启动；对sleep infinity模板需执行应用启动命令，再检查健康接口。";
                if (toolsEnabled) system += "\n同一项目的容器共享独立Docker网络，不同项目隔离。调用listProjectContainers取得容器name、internalPort和internalUrl，从临时测试容器通过http://容器名:内部端口访问同项目后端，不使用localhost或宿主机端口。localhost只代表当前容器；后端需监听0.0.0.0，测试前检查服务已启动。";
                if (toolsEnabled) system += "\n发布规则：纯HTML/CSS/JS无需容器，可直接发布含index.html的目录；Vue等必须先构建dist。静态URL固定为/sites/" + appId + "/，构建base/publicPath和history路由base必须匹配该前缀；纯HTML使用相对资源路径。不要把/src或未构建源码当产物。容器创建后返回两个宿主端口（均大于40000），必须读取返回映射，服务监听指定容器端口。前端访问已上线后端优先使用/services/{容器ID}/1/或/2/同域地址，不硬编码localhost或HTTP公网端口，避免HTTPS混合内容。后端路由按根路径实现，代理会移除前缀。";
                if (toolsEnabled) system += "\n若开放静态发布工具，publishStaticSite既可首次上线，也可更新已上线网站，与页面更新网站按钮相同。先getStaticSite查询当前发布目录；静态站点使用快照，修改文件不会自动更新线上内容。用户要求更新线上网站时，修改完成后重新调用publishStaticSite(directory)，不必先下线，URL保持不变；Vue等框架先重新构建再发布产物。只有工具返回成功才可说明线上网站已更新。";
                if (!Boolean.FALSE.equals(request.memoryEnabled())) {
                    var memory = memories.get(application.getOwnerUserId());
                    if (memory != null) system += "\n用户全局偏好，仅作为背景，不代表当前源码事实：\n" + memory.content();
                    if (toolsEnabled) system += "\n项目记忆为AGENTS.md。开始任务时通过文件工具读取，记录已确认的项目偏好与决策时可修改此文件，不写入密钥、对话流水或未经验证的结果。";
                    if (toolsEnabled) system += "\n若开放全局记忆工具，可先readGlobalMemory，再writeGlobalMemory更新用户明确表达的跨项目偏好，保留仍有效的内容。项目专属信息写项目记忆，不写全局记忆。";
                }
                context.add(SystemMessage.from(system));
                String clarificationHistory = clarifications.history(conversationId);
                if (!clarificationHistory.isEmpty()) context.add(UserMessage.from("当前会话之前已确认的需求细节，作为用户背景资料：" + clarificationHistory));
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
        contexts.clear(conversationId, run.getMemoryEnabled());
        active.put(run.getId(), state);
        state.timeoutSeconds = model.timeoutSeconds() + 10L;
        scheduleTimeout(state);
        workers.execute(() -> {
            try {
                var history = contexts.prepare(conversationId, run.getMemoryEnabled(), model, key,
                        compressor.estimate(context) + compressor.estimate(request.content()) + 12000, false, state.terminal::get);
                context.addAll(history);
                context.add(UserMessage.from(request.content()));
                if (state.terminal.get()) return;
                contexts.compactLive(conversationId, run.getMemoryEnabled(), context, 12000, model, key, state.terminal::get);
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
                        finish(state, null, ToolChatRunner.isModelTimeout(cause) ? "MODEL_TIMEOUT" : "MODEL_REQUEST_FAILED", false);
                    }
                };
                if (toolsEnabled) {
                    var toolContext = new ToolContext(appId, application.getOwnerUserId(), run.getId(), state.terminal::get,
                            waiting -> waitForUser(state, waiting), run.getMemoryEnabled());
                    var session = toolRegistry.bind(toolContext, enabledTools);
                    int toolTokens = compressor.estimate(session.specifications().toString());
                    toolRunner.start(streaming, context, session, handler, state.terminal::get, state,
                             current -> contexts.compactLive(conversationId, run.getMemoryEnabled(), current, toolTokens, model, key, state.terminal::get), model.timeoutSeconds());
                } else { toolRunner.startPlain(streaming, context, handler, state.terminal::get, state, model.timeoutSeconds()); }
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
                String savedContent = response == null ? state.content() : response;
                if (response != null || !savedContent.isBlank() || !state.snapshot().isEmpty()) {
                    var assistant = new MessageEntity();
                    assistant.setId(UUID.randomUUID().toString()); assistant.setConversationId(run.getConversationId());
                    assistant.setRole("ASSISTANT"); assistant.setContent(savedContent); assistant.setCreatedAt(OffsetDateTime.now());
                    if (response == null) assistant.setError(cancelled ? "已停止，保留已生成内容" : "回复未完整结束，保留已生成内容（" + errorCode + "）");
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
        } finally {
            contexts.clear(state.run.getConversationId(), state.run.getMemoryEnabled());
            active.remove(state.run.getId());
            state.changed();
            capacity.release();
        }
    }

    private void scheduleTimeout(Active state) {
        if (state.timeout != null) state.timeout.cancel(false);
        state.lastActivity = System.nanoTime();
        state.timeout = timer.scheduleWithFixedDelay(() -> {
            boolean expired;
            synchronized (state) {
                if ("WAITING_USER".equals(state.phase) || state.terminal.get()) return;
                if (contexts.busy(state.run.getConversationId())) { state.lastActivity = System.nanoTime(); return; }
                long idle = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - state.lastActivity);
                // 模型请求由请求级计时器负责重试，外层仅作为最终失活兜底
                long limit = "CALLING_TOOL".equals(state.phase) ? Math.max(150, state.timeoutSeconds) : state.timeoutSeconds + 30;
                expired = idle >= limit;
            }
            // 数据库写入不能阻塞唯一计时线程
            if (expired) workers.execute(() -> finish(state, null, "MODEL_TIMEOUT", false));
        }, 5, 5, TimeUnit.SECONDS);
    }

    private void waitForUser(Active state, boolean waiting) {
        synchronized (state) {
            if (state.terminal.get()) return;
            if (waiting) {
                if (state.timeout != null) state.timeout.cancel(false);
                state.phase = "WAITING_USER";
                state.pendingQuestion = clarifications.pending(state.run.getId());
                state.run.setProcessJson(RunStep.encode(state.snapshot()));
                runs.saveProcess(state.run.getId(), state.run.getProcessJson());
            } else {
                state.phase = "CALLING_TOOL";
                state.pendingQuestion = null;
                scheduleTimeout(state);
            }
            state.changed();
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
                contexts.clear(state.run.getConversationId(), state.run.getMemoryEnabled());
                if (state.timeout != null) state.timeout.cancel(false);
                active.remove(state.run.getId(), state);
                state.changed();
                capacity.release();
            }
        }
    }

    public RunView get(String appId, String conversationId, String runId) {
        conversations.requireOwned(appId, conversationId);
        return read(conversationId, runId);
    }
    public RunView latest(String appId, String conversationId) {
        conversations.requireOwned(appId, conversationId);
        var run = runs.latest(conversationId);
        if (run == null) return null;
        if (active.containsKey(run.getId()) || "RUNNING".equals(run.getStatus())) return read(conversationId, run.getId());
        var message = run.getAssistantMessageId() == null ? null : messages.selectOneById(run.getAssistantMessageId());
        return view(run, message == null ? "" : message.getContent());
    }

    public ConversationContextService.View context(String appId, String conversationId, boolean memory) {
        conversations.requireOwned(appId, conversationId);
        for (var state : active.values()) {
            if (!state.run.getConversationId().equals(conversationId)) continue;
            synchronized (state) {
                if (!state.terminal.get()) return contexts.view(conversationId, memory, state.run, state.snapshot(), state.content());
            }
        }
        return contexts.view(conversationId, memory);
    }

    public ConversationContextService.View compress(String appId, String conversationId, String modelId, boolean memory) {
        conversations.requireOwned(appId, conversationId);
        var model = settings.resolveModel(modelId);
        String key = models.credential(model);
        reserveCompression(appId, conversationId);
        try {
            contexts.prepare(conversationId, memory, model, key, 12000, true, () -> false);
            return contexts.view(conversationId, memory);
        } finally { contexts.release(conversationId); }
    }

    private void reserveCompression(String appId, String conversationId) {
        transactions.executeWithoutResult(status -> {
            if (applicationMapper.lockById(appId) == null || conversationMapper.lock(conversationId) == null) throw new IllegalStateException("会话已删除");
            var latest = runs.latest(conversationId);
            if (latest != null && "RUNNING".equals(latest.getStatus())) throw error("CONVERSATION_BUSY", "请等待当前任务结束后压缩", HttpStatus.CONFLICT);
            contexts.reserve(conversationId);
        });
    }

    public SseEmitter compressEvents(String appId, String conversationId, String modelId, boolean memory) {
        conversations.requireOwned(appId, conversationId);
        var model = settings.resolveModel(modelId);
        String key = models.credential(model);
        reserveCompression(appId, conversationId);
        var emitter = new SseEmitter(660000L);
        var task = CompletableFuture.supplyAsync(() -> {
            try {
            contexts.prepare(conversationId, memory, model, key, 12000, true, () -> false);
            return contexts.view(conversationId, memory);
            } finally { contexts.release(conversationId); }
        }, workers);
        workers.execute(() -> {
            try {
                while (!task.isDone()) {
                    emitter.send(SseEmitter.event().name("snapshot").data(contexts.view(conversationId, memory)));
                    Thread.sleep(150);
                }
                emitter.send(SseEmitter.event().name("done").data(task.join()));
                emitter.complete();
            } catch (java.util.concurrent.CompletionException error) {
                try { emitter.send(SseEmitter.event().name("failed").data(Map.of("message", "压缩失败，请确认模型可用且当前没有运行任务"))); emitter.complete(); }
                catch (Exception ignored) { }
            } catch (Exception error) { log.debug("Compression SSE ended, conversation={}", conversationId); }
        });
        return emitter;
    }

    private RunView read(String conversationId, String runId) {
        var live = active.get(runId);
        if (live != null) {
            synchronized (live) {
                if (!conversationId.equals(live.run.getConversationId())) throw error("RUN_NOT_FOUND", "对话任务不存在", HttpStatus.NOT_FOUND);
                // 正文、步骤、phase 必须来自同一次读取，避免增量基线前后不一致。
                var run = live.run;
                if (active.get(runId) == live) return new RunView(run.getId(), run.getConversationId(), "RUNNING", run.getModelId(), run.getReasoningEffort(),
                        run.getMemoryEnabled(), run.getUserMessageId(), null, live.content(), run.getErrorCode(),
                        run.getCreatedAt(), null, live.phase(), live.snapshot(), live.pendingQuestion);
            }
        }
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
        String user = cn.dev33.satoken.stp.StpUtil.getLoginIdAsString();
        acquireSubscription(user, runId);
        var emitter = new SseEmitter(0L);
        var closed = new AtomicBoolean();
        // 每个订阅最多保留一个变更通知，慢客户端不会积压 token/快照。
        var changes = new ArrayBlockingQueue<Boolean>(1);
        Runnable signal = () -> changes.offer(Boolean.TRUE);
        var live = active.get(runId);
        Runnable close = () -> {
            if (closed.compareAndSet(false, true)) {
                if (live != null) live.listeners.remove(signal);
                changes.offer(Boolean.TRUE);
                releaseSubscription(user, runId);
            }
        };
        emitter.onCompletion(close); emitter.onTimeout(close); emitter.onError(cause -> close.run());
        if (live != null) {
            live.listeners.add(signal);
            if (closed.get()) live.listeners.remove(signal);
        }
        signal.run();
        try { workers.execute(() -> {
            try {
                RunView previous = null;
                long sequence = 0;
                while (!closed.get()) {
                    if (changes.poll(15, TimeUnit.SECONDS) == null) {
                        if (!closed.get()) emitter.send(SseEmitter.event().comment("keepalive"));
                        continue;
                    }
                    if (closed.get()) break;
                    // 合并密集 token，最多约每 80ms 一次；不在模型回调里执行网络发送。
                    if (live != null && !live.terminal.get()) Thread.sleep(80);
                    if (closed.get()) break;
                    changes.clear();
                    var snapshot = read(conversationId, runId);
                    // 每条新连接先恢复快照，之后仅发送变化的文本和步骤。
                    if (previous == null) emitter.send(SseEmitter.event().name("snapshot").data(snapshot));
                    else if (!previous.equals(snapshot)) emitter.send(SseEmitter.event().name("delta").data(RunDelta.between(++sequence, previous, snapshot)));
                    previous = snapshot;
                    if (!"RUNNING".equals(snapshot.status())) {
                        emitter.send(SseEmitter.event().name("done").data(Map.of("id", runId)));
                        if (!closed.get()) emitter.complete();
                        break;
                    }
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                if (!closed.get()) emitter.complete();
            } catch (Exception exception) {
                log.debug("SSE subscription ended, run={}", runId);
                if (!closed.get()) emitter.completeWithError(exception);
            }
            finally { close.run(); }
        }); } catch (RuntimeException exception) { close.run(); throw exception; }
        return emitter;
    }

    private RunView view(AgentRun run, String text) {
        var state = active.get(run.getId());
        return new RunView(run.getId(), run.getConversationId(), run.getStatus(), run.getModelId(), run.getReasoningEffort(),
                run.getMemoryEnabled(), run.getUserMessageId(), run.getAssistantMessageId(), text, run.getErrorCode(),
                run.getCreatedAt(), run.getFinishedAt(),
                state == null ? run.getStatus() : state.phase(),
                state == null ? RunStep.decode(run.getProcessJson()) : state.snapshot(),
                state == null ? clarifications.pending(run.getId()) : state.pendingQuestion);
    }
    private String clip(String text, int limit) { return text.substring(0, Math.min(text.length(), limit)); }
    private BusinessException error(String code, String message, HttpStatus status) { return new BusinessException(code, message, status); }

    @PreDestroy
    public void shutdown() { workers.shutdownNow(); titles.shutdownNow(); timer.shutdownNow(); }
}
