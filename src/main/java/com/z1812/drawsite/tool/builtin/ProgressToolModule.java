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
        @Tool("整体更新progress.json，提供读取的expectedHash。根字段仅schemaVersion=1.0和features数组；每个节点必填唯一id、title、status，可选acceptanceCriteria字符串数组和notes文本。status仅TODO未完成/IN_PROGRESS进行中/DONE完成。完成表示开发节点完成，不代表测试通过。禁止其他字段")
        public Object writeProgress(String content, String expectedHash) throws Exception {
            validate(content);
            return files.write(app, "progress.json", content, expectedHash);
        }
    }
    public static void validate(String content) {
        com.z1812.drawsite.workspace.ProgressDocument.parse(content);
    }
}
