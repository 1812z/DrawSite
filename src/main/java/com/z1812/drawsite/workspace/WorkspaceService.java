package com.z1812.drawsite.workspace;

import org.eclipse.jgit.api.Git;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.z1812.drawsite.common.exception.BusinessException;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

@Service
public class WorkspaceService {
    public static final List<String> DOCUMENTS = List.of("AGENTS.md", "docs/requirements.md",
            "docs/design/overview.md", "docs/design/detail.md", "progress.json", "api/openapi.json");
    private final Path root;

    public WorkspaceService(@Value("${drawsite.workspace-root}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    public String initialize(String applicationId) throws Exception {
        Path directory = directory(applicationId);
        Files.createDirectories(root);
        if (Files.isSymbolicLink(root)) throw new IOException("Workspace root must not be a symbolic link");
        Files.createDirectory(directory);
        Files.createDirectories(directory.resolve("docs/design"));
        Files.createDirectories(directory.resolve("api/tests"));
        Files.writeString(directory.resolve(".gitignore"), "node_modules/\ntarget/\ndist/\n.env\n.env.*\n!.env.example\n", StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("AGENTS.md"), """
                # 项目共享上下文

                所有会话使用同一项目工作区。需求与设计以当前项目文件为准。
                - 需求规格：docs/requirements.md
                - 概要设计：docs/design/overview.md
                - 详细设计：docs/design/detail.md
                - API 契约：api/openapi.json（OpenAPI 3.1，operationId 必须唯一）
                - API 用例：api/tests/*.test.json
                - 开发进度：progress.json

                ## 维护规则
                先分析需求，再维护设计，最后编码；变更接口时同步更新契约、说明和测试用例。
                Markdown 内容自由组织，不把对话历史当作需求事实。
                progress.json 保持 schemaVersion=1.0，features 为数组；每项包含稳定 id、title、
                status、acceptanceCriteria（字符串数组），可选 notes。状态仅允许 TODO、IN_PROGRESS、
                READY_FOR_VERIFICATION、BLOCKED；实现后标为待验证，不自行宣称测试通过。
                不伪造平台测试结果；实际验证由平台执行器负责。不要向项目文件写入密钥或凭据。
                """, StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("docs/requirements.md"), "# 需求规格说明书\n\n等待需求分析。\n", StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("docs/design/overview.md"), "# 概要设计\n\n等待系统设计。\n", StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("docs/design/detail.md"), "# 详细设计\n\n等待详细设计。\n", StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("progress.json"), "{\"schemaVersion\":\"1.0\",\"features\":[]}\n", StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("api/openapi.json"), "{\"openapi\":\"3.1.0\",\"info\":{\"title\":\"Application API\",\"version\":\"0.1.0\"},\"paths\":{}}\n", StandardCharsets.UTF_8);
        try (Git git = Git.init().setDirectory(directory.toFile()).setInitialBranch("main").call()) {
            git.add().addFilepattern(".").call();
            return git.commit().setMessage("Initialize application documents")
                    .setAuthor("DrawSite", "drawsite@localhost").setCommitter("DrawSite", "drawsite@localhost")
                    .call().getName();
        }
    }

    public String readDocument(String applicationId, String path) throws IOException {
        if (!DOCUMENTS.contains(path)) throw new BusinessException("DOCUMENT_NOT_FOUND", "文档不存在", HttpStatus.NOT_FOUND);
        Path directory = directory(applicationId);
        Path file = directory.resolve(path).normalize();
        for (Path current = file; current != null && current.startsWith(root); current = current.getParent()) {
            if (Files.isSymbolicLink(current)) throw new IOException("Symbolic link in workspace");
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new BusinessException("DOCUMENT_NOT_FOUND", "文档不存在", HttpStatus.NOT_FOUND);
        }
        if (Files.size(file) > 2 * 1024 * 1024) throw new BusinessException("DOCUMENT_TOO_LARGE", "文档超过读取限制", HttpStatus.BAD_REQUEST);
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private Path directory(String applicationId) {
        return root.resolve(UUID.fromString(applicationId).toString());
    }
}
