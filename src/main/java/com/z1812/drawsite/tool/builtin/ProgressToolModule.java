package com.z1812.drawsite.tool.builtin;

import com.z1812.drawsite.tool.framework.*;
import com.z1812.drawsite.workspace.ProjectFileService;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;

@Component
public class ProgressToolModule implements ProjectToolModule {
    private final ProjectFileService files;
    public ProgressToolModule(ProjectFileService files) { this.files = files; }
    @Override public String id() { return "progress"; }
    @Override public String name() { return "进度管理"; }
    @Override public String description() { return "查看并更新项目开发进度"; }
    @Override public Object bind(ToolContext context) { return new ProgressTools(files, context.applicationId()); }
    public record ProgressTools(ProjectFileService files, String app) {
        @Tool("读取progress.json开发进度及sha256")
        public Object readProgress() throws Exception { return files.read(app, "progress.json", 1, 500); }
        @Tool("整体更新progress.json，提供读取的expectedHash。schemaVersion必须1.0，features数组每项包含唯一id、title、status、acceptanceCriteria字符串数组，可选notes。status仅TODO/IN_PROGRESS/READY_FOR_VERIFICATION/BLOCKED，禁止自行宣称测试通过")
        public Object writeProgress(String content, String expectedHash) throws Exception {
            validate(content);
            return files.write(app, "progress.json", content, expectedHash);
        }
    }
    public static void validate(String content) {
        if (content == null || content.length() > 100000) throw new IllegalArgumentException("进度文档最多100000字符");
        var root = JsonMapper.builder().build().readTree(content);
        if (!root.isObject() || !"1.0".equals(root.path("schemaVersion").asText()) || !root.path("features").isArray()
                || root.path("features").size() > 500) throw new IllegalArgumentException("需要schemaVersion=1.0及features数组（最多500项）");
        var ids = new HashSet<String>();
        for (var feature : root.path("features")) {
            if (!feature.isObject() || !feature.path("id").isString() || feature.path("id").asText().isBlank()
                    || !ids.add(feature.path("id").asText()) || !feature.path("title").isString() || feature.path("title").asText().isBlank()
                    || !Set.of("TODO", "IN_PROGRESS", "READY_FOR_VERIFICATION", "BLOCKED").contains(feature.path("status").asText())
                    || !feature.path("acceptanceCriteria").isArray()) throw new IllegalArgumentException("进度项字段不完整、ID重复或状态无效");
            for (var criterion : feature.path("acceptanceCriteria")) if (!criterion.isString() || criterion.asText().isBlank()) throw new IllegalArgumentException("验收标准必须是非空字符串");
            if (feature.has("notes") && !feature.path("notes").isString()) throw new IllegalArgumentException("notes必须是字符串");
        }
    }
}
