package com.z1812.drawsite.testing;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;

public final class ApiDocuments {
    public static final String CONTRACT = "api/openapi.json", SUITE = "api/tests/suite.test.json";
    public static final JsonMapper JSON = JsonMapper.builder().enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    private static final Set<String> METHODS = Set.of("get", "post", "put", "patch", "delete", "head", "options");
    public record Operation(String operationId, String method, String path, List<Parameter> parameters, JsonNode requestSchema, Map<String, JsonNode> responses) {}
    public record Parameter(String name, String in, boolean required, JsonNode schema) {}
    public record Target(String containerId, int port, String basePath) {}
    public record Check(String pointer, JsonNode equals) {}
    public record Case(String id, String title, String operationId, String target, Map<String, String> path,
                       Map<String, String> query, Map<String, String> headers, JsonNode body, int expectedStatus, List<Check> checks) {}
    public record Suite(String schemaVersion, String title, Map<String, Target> targets, List<Case> cases) {}
    public static JsonNode tree(String text) {
        if (text == null || text.length() > 500000) throw new IllegalArgumentException("API文档最多500000字符");
        return JSON.readTree(text);
    }
    public static void fields(JsonNode node, Set<String> allowed, Set<String> required) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException("需要JSON对象");
        for (String key : node.propertyNames()) if (!allowed.contains(key)) throw new IllegalArgumentException("不支持字段：" + key);
        for (String key : required) if (!node.has(key)) throw new IllegalArgumentException("缺少字段：" + key);
    }
    private static String text(JsonNode n, String key) {
        if (!n.path(key).isString() || n.path(key).asText().isBlank() || n.path(key).asText().length() > 500) throw new IllegalArgumentException("无效文本字段：" + key);
        return n.path(key).asText();
    }
    public static List<Operation> contract(String content) {
        var root = tree(content);
        fields(root, Set.of("openapi", "info", "paths"), Set.of("openapi", "info", "paths"));
        if (!"3.1.0".equals(text(root, "openapi"))) throw new IllegalArgumentException("仅支持OpenAPI 3.1.0受限子集");
        fields(root.path("info"), Set.of("title", "version", "description"), Set.of("title", "version"));
        text(root.path("info"), "title"); text(root.path("info"), "version");
        if (!root.path("paths").isObject()) throw new IllegalArgumentException("paths必须是对象");
        var result = new ArrayList<Operation>(); var ids = new HashSet<String>();
        for (String path : root.path("paths").propertyNames()) {
            if (!path.matches("/[A-Za-z0-9_./{}-]*") || path.contains("..")) throw new IllegalArgumentException("无效API路径");
            var methods = root.path("paths").path(path);
            fields(methods, METHODS, Set.of());
            for (String method : methods.propertyNames()) {
                var op = methods.path(method);
                fields(op, Set.of("operationId", "summary", "description", "parameters", "requestBody", "responses"), Set.of("operationId", "responses"));
                String id = text(op, "operationId");
                if (!id.matches("[A-Za-z0-9_.-]{1,100}") || !ids.add(id)) throw new IllegalArgumentException("operationId必须唯一且仅包含字母数字_.-");
                var parameters = new ArrayList<Parameter>(); var names = new HashSet<String>();
                if (op.has("parameters")) {
                    if (!op.path("parameters").isArray()) throw new IllegalArgumentException("parameters必须是数组");
                    for (var p : op.path("parameters")) {
                        fields(p, Set.of("name", "in", "required", "schema", "description"), Set.of("name", "in", "schema"));
                        String name = text(p, "name"), in = text(p, "in");
                        if (!Set.of("path", "query", "header").contains(in) || !names.add(in + ":" + name.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("参数位置不支持或参数重复");
                        if (p.has("required") && !p.path("required").isBoolean()) throw new IllegalArgumentException("required必须是布尔值");
                        boolean required = p.path("required").asBoolean(false);
                        if (in.equals("path") && (!required || !path.contains("{" + name + "}"))) throw new IllegalArgumentException("路径参数必须required=true并匹配路径");
                        schema(p.path("schema")); parameters.add(new Parameter(name, in, required, p.path("schema")));
                    }
                }
                var placeholders = java.util.regex.Pattern.compile("\\{([^}]+)}").matcher(path);
                while (placeholders.find()) if (!names.contains("path:" + placeholders.group(1).toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("路径占位符缺少参数声明");
                JsonNode request = null;
                if (op.has("requestBody")) {
                    var body = op.path("requestBody"); fields(body, Set.of("required", "content", "description"), Set.of("content"));
                    request = mediaSchema(body.path("content"));
                }
                if (!op.path("responses").isObject() || op.path("responses").isEmpty()) throw new IllegalArgumentException("responses不能为空");
                var responses = new LinkedHashMap<String, JsonNode>();
                for (String code : op.path("responses").propertyNames()) {
                    if (!code.matches("[1-5][0-9]{2}")) throw new IllegalArgumentException("响应必须使用精确HTTP状态码");
                    var response = op.path("responses").path(code);
                    fields(response, Set.of("description", "content"), Set.of("description")); text(response, "description");
                    responses.put(code, response.has("content") ? mediaSchema(response.path("content")) : null);
                }
                result.add(new Operation(id, method.toUpperCase(Locale.ROOT), path, parameters, request, responses));
                if (result.size() > 200) throw new IllegalArgumentException("最多200个API");
            }
        }
        return result;
    }
    private static JsonNode mediaSchema(JsonNode content) {
        fields(content, Set.of("application/json"), Set.of("application/json"));
        fields(content.path("application/json"), Set.of("schema"), Set.of("schema"));
        var s = content.path("application/json").path("schema"); schema(s); return s;
    }
    public static void schema(JsonNode s) {
        schema(s, 0);
    }
    private static void schema(JsonNode s, int depth) {
        if (depth > 20) throw new IllegalArgumentException("schema嵌套最多20层");
        fields(s, Set.of("type", "properties", "required", "additionalProperties", "items", "enum", "description", "minimum", "maximum", "minLength", "maxLength"), Set.of("type"));
        String type = text(s, "type");
        if (!Set.of("object", "array", "string", "integer", "number", "boolean", "null").contains(type)) throw new IllegalArgumentException("不支持schema类型");
        if (type.equals("object")) {
            if (!s.path("properties").isObject() || !s.path("additionalProperties").isBoolean() || s.path("additionalProperties").asBoolean()) throw new IllegalArgumentException("对象schema必须明确properties及additionalProperties=false");
            for (String key : s.path("properties").propertyNames()) schema(s.path("properties").path(key), depth + 1);
            if (s.has("required")) {
                if (!s.path("required").isArray()) throw new IllegalArgumentException("required必须为数组");
                for (var key : s.path("required")) if (!key.isString() || !s.path("properties").has(key.asText())) throw new IllegalArgumentException("required引用未知属性");
            }
        }
        if (type.equals("array")) schema(s.path("items"), depth + 1);
        if (s.has("enum") && (!s.path("enum").isArray() || s.path("enum").isEmpty())) throw new IllegalArgumentException("enum不能为空");
        for (String key : List.of("minimum", "maximum", "minLength", "maxLength")) if (s.has(key) && !s.path(key).isNumber()) throw new IllegalArgumentException(key + "必须为数字");
    }
    public static void validate(JsonNode value, JsonNode s, String path) {
        String type = s.path("type").asText();
        boolean valid = switch (type) {
            case "object" -> value.isObject(); case "array" -> value.isArray(); case "string" -> value.isString();
            case "integer" -> value.isIntegralNumber(); case "number" -> value.isNumber(); case "boolean" -> value.isBoolean(); case "null" -> value.isNull(); default -> false;
        };
        if (!valid) throw new IllegalArgumentException(path + "类型不匹配：" + type);
        if (s.has("enum")) { boolean found = false; for (var option : s.path("enum")) if (option.equals(value)) found = true; if (!found) throw new IllegalArgumentException(path + "不在枚举范围"); }
        if (value.isObject()) {
            for (var key : s.path("required")) if (!value.has(key.asText())) throw new IllegalArgumentException(path + "缺少属性：" + key.asText());
            for (String key : value.propertyNames()) { if (!s.path("properties").has(key)) throw new IllegalArgumentException(path + "多余属性：" + key); validate(value.path(key), s.path("properties").path(key), path + "/" + key); }
        }
        if (value.isArray()) for (var item : value) validate(item, s.path("items"), path + "[]");
        if (value.isNumber() && ((s.has("minimum") && value.asDouble() < s.path("minimum").asDouble()) || (s.has("maximum") && value.asDouble() > s.path("maximum").asDouble()))) throw new IllegalArgumentException(path + "数值越界");
        if (value.isString() && ((s.has("minLength") && value.asText().length() < s.path("minLength").asInt()) || (s.has("maxLength") && value.asText().length() > s.path("maxLength").asInt()))) throw new IllegalArgumentException(path + "长度越界");
    }
    public static Suite suite(String content, List<Operation> operations) {
        var root = tree(content);
        fields(root, Set.of("schemaVersion", "title", "targets", "cases"), Set.of("schemaVersion", "title", "targets", "cases"));
        Suite suite = JSON.treeToValue(root, Suite.class);
        if (!"1.0".equals(suite.schemaVersion()) || suite.title() == null || suite.title().isBlank() || suite.targets() == null || suite.cases() == null || suite.cases().isEmpty() || suite.cases().size() > 200) throw new IllegalArgumentException("测试需要schemaVersion=1.0、标题、targets及1至200条cases");
        for (var target : suite.targets().entrySet()) {
            var t = target.getValue();
            if (!target.getKey().matches("[A-Za-z0-9_-]{1,64}") || t == null || t.containerId() == null || !t.containerId().matches("[a-fA-F0-9-]{36}") || t.port() < 1 || t.port() > 65535 || t.basePath() == null || (!t.basePath().isEmpty() && !t.basePath().matches("/[A-Za-z0-9_/-]*")) || t.basePath().contains("..")) throw new IllegalArgumentException("无效测试目标，使用containerId、内部port和basePath");
        }
        var ids = new HashSet<String>();
        if (suite.targets().size() > 20 || suite.title().length() > 300) throw new IllegalArgumentException("目标最多20个，标题最多300字符");
        for (var c : suite.cases()) {
            if (c == null || c.id() == null || !c.id().matches("[A-Za-z0-9_.-]{1,100}") || !ids.add(c.id()) || c.title() == null || c.title().isBlank() || c.title().length() > 300 || !suite.targets().containsKey(c.target())) throw new IllegalArgumentException("用例id、title或target无效");
            var op = operations.stream().filter(o -> o.operationId().equals(c.operationId())).findFirst().orElseThrow(() -> new IllegalArgumentException("用例引用了契约外operationId"));
            if (!op.responses().containsKey(Integer.toString(c.expectedStatus()))) throw new IllegalArgumentException("expectedStatus未在契约声明");
            for (var entry : Map.of("path", c.path() == null ? Map.<String,String>of() : c.path(), "query", c.query() == null ? Map.<String,String>of() : c.query(), "header", c.headers() == null ? Map.<String,String>of() : c.headers()).entrySet()) {
                for (var p : entry.getValue().entrySet()) {
                    if (!p.getKey().matches("[A-Za-z0-9_.-]{1,100}")) throw new IllegalArgumentException("参数名不合法");
                    if (p.getValue() == null || p.getValue().length() > 10000 || p.getValue().contains("\r") || p.getValue().contains("\n")) throw new IllegalArgumentException("参数值不合法");
                    if (entry.getKey().equals("header") && p.getKey().equalsIgnoreCase("Content-Type")) continue;
                    if (op.parameters().stream().noneMatch(param -> param.in().equals(entry.getKey()) && param.name().equalsIgnoreCase(p.getKey()))) throw new IllegalArgumentException("用例使用了未声明参数：" + p.getKey());
                }
            }
            if (c.body() != null && !c.body().isNull() && op.requestSchema() == null) throw new IllegalArgumentException("契约未声明请求体");
            if (c.checks() != null) {
                if (c.checks().size() > 50) throw new IllegalArgumentException("每条用例最多50个断言");
                for (var check : c.checks()) if (check == null || check.pointer() == null || (!check.pointer().isEmpty() && !check.pointer().startsWith("/")) || check.equals() == null) throw new IllegalArgumentException("checks需要JSON Pointer及equals值");
            }
        }
        return suite;
    }
}
