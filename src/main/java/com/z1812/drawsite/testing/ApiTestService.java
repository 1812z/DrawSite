package com.z1812.drawsite.testing;

import com.z1812.drawsite.application.ApplicationMapper;
import com.z1812.drawsite.docker.*;
import com.z1812.drawsite.workspace.ProjectFileService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import jakarta.annotation.PreDestroy;
import tools.jackson.databind.JsonNode;
import java.util.*;
import java.time.OffsetDateTime;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import static com.z1812.drawsite.testing.ApiDocuments.*;

@Service
public class ApiTestService implements ApplicationRunner {
    private final ProjectFileService files;
    private final ContainerService containers;
    private final DockerEngine docker;
    private final ApiTestMapper mapper;
    private final ApplicationMapper applications;
    private final TransactionTemplate tx;
    // 小型服务器串行执行测试，避免额外创建测试容器
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Semaphore capacity = new Semaphore(10);
    public ApiTestService(ProjectFileService files, ContainerService containers, DockerEngine docker, ApiTestMapper mapper, ApplicationMapper applications, TransactionTemplate tx) {
        this.files = files; this.containers = containers; this.docker = docker; this.mapper = mapper; this.applications = applications; this.tx = tx;
    }
    @Override public void run(ApplicationArguments args) { mapper.recover(); }
    public record Document(String path, String content, String sha256) {}
    public Document read(String app, String path) throws Exception { return new Document(path, files.readText(app, path), files.read(app, path, 1, 500).sha256()); }
    public Object writeContract(String app, String content, String hash) throws Exception { contract(content); return files.write(app, CONTRACT, content, hash); }
    public Object writeSuite(String app, String content, String hash) throws Exception { suite(content, contract(files.readText(app, CONTRACT))); return files.write(app, SUITE, content, hash); }
    public record State(Document contract, Document suite, List<ApiTestRun> runs) {}
    public State state(String app) throws Exception {
        Document c = null, s = null;
        if (files.exists(app, CONTRACT)) c = read(app, CONTRACT);
        if (files.exists(app, SUITE)) s = read(app, SUITE);
        return new State(c, s, mapper.history(app));
    }
    public ApiTestRun report(String app, String id) {
        var run = mapper.selectOneById(id);
        if (run == null || !app.equals(run.getApplicationId())) throw new IllegalArgumentException("测试记录不存在");
        return run;
    }
    public Object generate(String app, String target, String hash) throws Exception {
        var operations = contract(files.readText(app, CONTRACT));
        if (operations.isEmpty()) throw new IllegalArgumentException("契约没有API，无法生成用例");
        Target t = JSON.readValue(target, Target.class);
        var targets = Map.of("backend", t);
        var cases = new ArrayList<Case>();
        for (var op : operations) {
            var path = new LinkedHashMap<String,String>(); var query = new LinkedHashMap<String,String>(); var headers = new LinkedHashMap<String,String>();
            for (var p : op.parameters()) if (p.required()) {
                String value = sample(p.schema()).asText();
                switch (p.in()) { case "path" -> path.put(p.name(), value); case "query" -> query.put(p.name(), value); case "header" -> headers.put(p.name(), value); }
            }
            int code = Integer.parseInt(op.responses().keySet().stream().filter(c -> c.startsWith("2")).findFirst().orElse(op.responses().keySet().iterator().next()));
            cases.add(new Case(op.operationId() + "-basic", op.operationId() + " 基础用例", op.operationId(), "backend", path, query, headers,
                    op.requestSchema() == null ? null : sample(op.requestSchema()), code, List.of()));
        }
        String content = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(new Suite("1.0", "API 基础测试", targets, cases));
        return writeSuite(app, content, hash);
    }
    private JsonNode sample(JsonNode schema) {
        if (schema.has("enum")) return schema.path("enum").get(0);
        return switch (schema.path("type").asText()) {
            case "object" -> { var value = JSON.createObjectNode(); for (var key : schema.path("required")) value.set(key.asText(), sample(schema.path("properties").path(key.asText()))); yield value; }
            case "array" -> JSON.createArrayNode().add(sample(schema.path("items")));
            case "integer" -> JSON.valueToTree(schema.has("minimum") ? schema.path("minimum").asInt() : 1);
            case "number" -> JSON.valueToTree(schema.has("minimum") ? schema.path("minimum").asDouble() : 1.0);
            case "boolean" -> JSON.valueToTree(true);
            case "null" -> JSON.valueToTree(null);
            default -> JSON.valueToTree("example");
        };
    }
    public ApiTestRun start(String app, String owner) throws Exception {
        var operations = contract(files.readText(app, CONTRACT));
        if (operations.isEmpty()) throw new IllegalArgumentException("请先编写有效API契约");
        var definition = suite(files.readText(app, SUITE), operations);
        var targets = new LinkedHashMap<String, ContainerEntity>();
        for (var entry : definition.targets().entrySet()) {
            var container = containers.require(entry.getValue().containerId(), owner, false);
            if (!app.equals(container.getApplicationId()) || !"RUNNING".equals(container.getStatus())) throw new IllegalArgumentException("测试目标必须为当前项目运行中的容器");
            targets.put(entry.getKey(), container);
        }
        if (targets.isEmpty()) throw new IllegalArgumentException("测试目标不能为空");
        String runner = null;
        for (var container : targets.values()) {
            if (docker.run(5, "exec", container.getDockerId(), "curl", "--version").exitCode() == 0) { runner = container.getDockerId(); break; }
        }
        if (runner == null) throw new IllegalArgumentException("测试目标容器中至少有一个需要安装curl，请让AI准备执行环境");
        final String runnerId = runner;
        if (!capacity.tryAcquire()) throw new IllegalArgumentException("测试队列已满，请稍后重试");
        try {
            var run = tx.execute(status -> {
                if (applications.lockOwned(app, owner) == null) throw new IllegalArgumentException("项目不存在");
                if (mapper.running(app) > 0) throw new IllegalArgumentException("当前项目已有测试任务");
                var item = new ApiTestRun(); item.setId(UUID.randomUUID().toString()); item.setApplicationId(app); item.setTitle(definition.title());
                item.setStatus("RUNNING"); item.setTotal(definition.cases().size()); item.setCreatedAt(OffsetDateTime.now());
                item.setReportJson(JSON.writeValueAsString(Map.of("schemaVersion", "1.0", "results", List.of()))); mapper.insert(item); return item;
            });
            executor.execute(() -> execute(run, definition, operations, targets, runnerId)); return run;
        } catch (RuntimeException error) { capacity.release(); throw error; }
    }
    private void execute(ApiTestRun run, Suite suite, List<Operation> operations, Map<String, ContainerEntity> targets, String runner) {
        var results = new ArrayList<Map<String,Object>>();
        try {
            for (var c : suite.cases()) {
                if (Thread.currentThread().isInterrupted() || mapper.selectOneById(run.getId()) == null) throw new IllegalStateException("测试已停止或项目已删除");
                var op = operations.stream().filter(o -> o.operationId().equals(c.operationId())).findFirst().orElseThrow();
                var target = suite.targets().get(c.target());
                String path = op.path();
                if (c.path() != null) for (var p : c.path().entrySet()) path = path.replace("{" + p.getKey() + "}", encode(p.getValue()));
                var item = new LinkedHashMap<String,Object>();
                item.put("id", c.id()); item.put("title", c.title()); item.put("operationId", op.operationId()); item.put("method", op.method()); item.put("path", op.path());
                item.put("target", c.target()); item.put("expectedStatus", c.expectedStatus());
                long started = System.nanoTime();
                try {
                    if (path.contains("{") || path.contains("}")) throw new IllegalArgumentException("缺少路径参数");
                    String base = target.basePath().replaceAll("/+$", "");
                    String url = "http://" + targets.get(c.target()).getName() + ":" + target.port() + base + path;
                    if (c.query() != null && !c.query().isEmpty()) url += "?" + c.query().entrySet().stream().map(p -> encode(p.getKey()) + "=" + encode(p.getValue())).collect(java.util.stream.Collectors.joining("&"));
                    var args = new ArrayList<>(List.of("exec", runner, "curl", "--silent", "--show-error", "--globoff", "--max-time", "10", "--max-filesize", "65536", "--max-redirs", "0", "--request", op.method(), "--write-out", "\nDRAWSITE_STATUS:%{http_code}", "--url", url));
                    if (op.method().equals("HEAD")) args.add("--head");
                    if (c.headers() != null) for (var h : c.headers().entrySet()) { args.add("--header"); args.add(h.getKey() + ": " + h.getValue()); }
                    if (c.body() != null && !c.body().isNull()) { args.addAll(List.of("--header", "Content-Type: application/json", "--data-raw", JSON.writeValueAsString(c.body()))); }
                    var response = docker.run(15, args.toArray(String[]::new));
                    if (response.exitCode() != 0) throw new IllegalArgumentException("请求失败（执行容器需安装curl）：" + clip(response.output(), 1000));
                    int split = response.output().lastIndexOf("\nDRAWSITE_STATUS:");
                    if (split < 0) throw new IllegalArgumentException("无法解析HTTP响应");
                    int code = Integer.parseInt(response.output().substring(split + 17).strip());
                    String body = response.output().substring(0, split);
                    item.put("actualStatus", code); item.put("responseBody", clip(body, 8000));
                    if (code != c.expectedStatus()) throw new IllegalArgumentException("HTTP状态不符：" + code);
                    JsonNode responseSchema = op.responses().get(Integer.toString(code));
                    JsonNode json = null;
                    if (responseSchema != null || (c.checks() != null && !c.checks().isEmpty())) json = JSON.readTree(body);
                    if (responseSchema != null) validate(json, responseSchema, "response");
                    if (c.checks() != null) for (var check : c.checks()) if (json == null || !json.at(check.pointer()).equals(check.equals())) throw new IllegalArgumentException("断言失败：" + check.pointer());
                    item.put("status", "PASSED"); run.setPassed(run.getPassed() + 1);
                } catch (Exception error) { item.put("status", "FAILED"); item.put("error", clip(error.getMessage(), 1500)); }
                item.put("durationMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)); results.add(item);
                run.setCompleted(results.size()); persist(run, suite, results);
            }
            run.setStatus("COMPLETED");
        } catch (Exception error) { run.setStatus("FAILED"); }
        finally { run.setFinishedAt(OffsetDateTime.now()); try { persist(run, suite, results); } finally { capacity.release(); } }
    }
    private void persist(ApiTestRun run, Suite suite, List<Map<String,Object>> results) {
        run.setReportJson(JSON.writeValueAsString(Map.of("schemaVersion", "1.0", "runId", run.getId(), "title", run.getTitle(), "status", run.getStatus(), "total", run.getTotal(), "passed", run.getPassed(), "completed", run.getCompleted(), "targets", suite.targets(), "results", results)));
        mapper.update(run);
    }
    private static String encode(String text) { return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20"); }
    private static String clip(String text, int max) { return text == null ? "执行失败" : text.substring(0, Math.min(max, text.length())); }
    @PreDestroy public void close() { executor.shutdownNow(); }
}
