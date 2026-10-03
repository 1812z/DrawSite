package com.z1812.drawsite.tool.builtin;

import com.z1812.drawsite.tool.framework.ProjectToolModule;
import com.z1812.drawsite.tool.framework.ToolContext;

import com.z1812.drawsite.workspace.ProjectFileService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

@Component
public class FileToolModule implements ProjectToolModule {
    private final ProjectFileService files;
    public FileToolModule(ProjectFileService files) { this.files = files; }
    @Override public String id() { return "file"; }
    @Override public String name() { return "文件操作"; }
    @Override public String description() { return "支持查看并编辑项目文件"; }
    @Override public Object bind(ToolContext context) { return new FileTools(files, context.applicationId()); }

    public static class FileTools {
        private final ProjectFileService files;
        private final String applicationId;
        public FileTools(ProjectFileService files, String applicationId) { this.files = files; this.applicationId = applicationId; }

        @Tool("列出当前项目目录，directory为空表示根目录，depth为1至5")
        public Object listProjectFiles(String directory, int depth) throws Exception {
            return files.list(applicationId, directory, depth);
        }

        @Tool("读取UTF-8文件，返回内容和完整文件sha256；startLine从1开始，lineCount最多500")
        public Object readProjectFile(String path, int startLine, int lineCount) throws Exception {
            return files.read(applicationId, path, startLine, lineCount);
        }

        @Tool("在项目目录搜索字面文本，directory为空搜索根目录，返回路径及行号")
        public Object searchProjectCode(String directory, String query) throws Exception {
            return files.search(applicationId, directory, query);
        }

        @Tool("写入完整文件，新建expectedHash为NEW，覆盖须先读取并提供sha256")
        public Object writeProjectFile(String path, String content, String expectedHash) throws Exception {
            return files.write(applicationId, path, content, expectedHash);
        }

        @Tool("对一个文件应用严格unified diff，包含---/+++文件头及@@行号，不接受模糊匹配")
        public Object applyProjectPatch(String path, @P("单文件 unified diff 文本") String diff, String expectedHash) throws Exception {
            return files.patch(applicationId, path, diff, expectedHash);
        }

        @Tool("精确替换唯一匹配的文本，保留原换行，适合局部编辑或CRLF文件")
        public Object replaceProjectText(String path, String oldText, String newText, String expectedHash) throws Exception {
            return files.replace(applicationId, path, oldText, newText, expectedHash);
        }

        @Tool("删除一个普通文件，须提供读取获得的sha256，不支持递归删除")
        public Object deleteProjectFile(String path, String expectedHash) throws Exception {
            return files.delete(applicationId, path, expectedHash);
        }

        @Tool("移动或重命名普通文件，目标必须不存在，expectedHash来自源文件")
        public Object moveProjectFile(String source, String target, String expectedHash) throws Exception {
            return files.move(applicationId, source, target, expectedHash);
        }

        @Tool("创建项目相对目录，自动创建父目录")
        public Object createProjectDirectory(String path) throws Exception { return files.mkdir(applicationId, path); }
    }
}
