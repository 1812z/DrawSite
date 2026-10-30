package com.z1812.drawsite.tool.builtin;

import com.z1812.drawsite.tool.framework.*;
import com.z1812.drawsite.workspace.ProjectGitService;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

@Component
public class GitToolModule implements ProjectToolModule {
    private final ProjectGitService git;
    public GitToolModule(ProjectGitService git) { this.git = git; }
    public String id() { return "git"; }
    public String name() { return "Git"; }
    public String description() { return "管理项目版本并自动提交"; }
    public Object bind(ToolContext context) { return new GitTools(git, context.applicationId()); }
    public record GitTools(ProjectGitService git, String app) {
        @Tool("查看Git HEAD与可提交代码，提交前获取expectedHead。平台文档、AGENTS.md、progress.json、api/openapi.json及api/tests/故意不进Git；仅修改它们时clean=true正常，无需调查.gitignore或尝试空提交")
        public Object gitStatus() throws Exception { return git.status(app); }
        @Tool("完成一个独立代码功能或修复后提交，简单任务完成后提交一次即可。gitStatus.clean=true不调用本工具。平台文档/进度/契约不提交。message使用feat:或fix:，expectedHead来自gitStatus，不得宣称测试通过")
        public Object gitCommit(String message, String expectedHead) throws Exception { return git.commit(app, message, expectedHead); }
    }
}
