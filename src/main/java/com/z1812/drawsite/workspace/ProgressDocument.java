package com.z1812.drawsite.workspace;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;

public final class ProgressDocument {
    private ProgressDocument() {}
    public record Node(String id, String title, String status, List<String> acceptanceCriteria, String notes) {}
    public record Summary(String schemaVersion, List<Node> features, int completed, int inProgress, int pending,
                          int total, String completion, int percent) {}

    public static Summary parse(String content) {
        if (content == null || content.length() > 100000) throw new IllegalArgumentException("进度文档最多100000字符");
        JsonNode root;
        try {
            root = JsonMapper.builder().enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build().readTree(content);
        } catch (RuntimeException error) { throw new IllegalArgumentException("进度必须是合法JSON，不能包含重复字段或多余内容"); }
        fields(root, Set.of("schemaVersion", "features"), Set.of("schemaVersion", "features"));
        if (!root.path("schemaVersion").isString() || !"1.0".equals(root.path("schemaVersion").asText())
                || !root.path("features").isArray() || root.path("features").size() > 500) {
            throw new IllegalArgumentException("需要schemaVersion=1.0及features数组（最多500个节点）");
        }
        var ids = new HashSet<String>();
        var nodes = new ArrayList<Node>();
        int completed = 0, inProgress = 0;
        for (var node : root.path("features")) {
            fields(node, Set.of("id", "title", "status", "acceptanceCriteria", "notes"), Set.of("id", "title", "status"));
            String id = text(node, "id", 64), title = text(node, "title", 200), status = text(node, "status", 20);
            if (!id.matches("[a-zA-Z0-9_.-]{1,64}") || !ids.add(id)) throw new IllegalArgumentException("节点id必须唯一，且仅含字母、数字、下划线、点或短横线");
            if (!Set.of("TODO", "IN_PROGRESS", "DONE").contains(status)) throw new IllegalArgumentException("节点状态仅允许TODO（未完成）、IN_PROGRESS（进行中）、DONE（完成）");
            var criteria = new ArrayList<String>();
            if (node.has("acceptanceCriteria")) {
                var array = node.path("acceptanceCriteria");
                if (!array.isArray() || array.size() > 20) throw new IllegalArgumentException("acceptanceCriteria必须是最多20项的字符串数组");
                for (var item : array) {
                    if (!item.isString() || item.asText().isBlank() || item.asText().length() > 500) throw new IllegalArgumentException("验收标准必须为1至500字符的文本");
                    criteria.add(item.asText());
                }
            }
            String notes = node.has("notes") ? textAllowEmpty(node, "notes", 2000) : "";
            nodes.add(new Node(id, title, status, List.copyOf(criteria), notes));
            if ("DONE".equals(status)) completed++;
            if ("IN_PROGRESS".equals(status)) inProgress++;
        }
        int total = nodes.size();
        return new Summary("1.0", List.copyOf(nodes), completed, inProgress, total - completed - inProgress,
                total, completed + "/" + total, total == 0 ? 0 : completed * 100 / total);
    }
    private static void fields(JsonNode node, Set<String> allowed, Set<String> required) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException("进度及节点必须是JSON对象");
        for (String name : node.propertyNames()) if (!allowed.contains(name)) throw new IllegalArgumentException("未知进度字段: " + name);
        for (String name : required) if (!node.has(name)) throw new IllegalArgumentException("缺少进度字段: " + name);
    }
    private static String text(JsonNode node, String name, int max) {
        String value = textAllowEmpty(node, name, max);
        if (value.isBlank()) throw new IllegalArgumentException(name + "不能为空");
        return value;
    }
    private static String textAllowEmpty(JsonNode node, String name, int max) {
        var value = node.path(name);
        if (!value.isString() || value.asText().length() > max) throw new IllegalArgumentException(name + "必须为最多" + max + "字符的文本");
        return value.asText();
    }
}
