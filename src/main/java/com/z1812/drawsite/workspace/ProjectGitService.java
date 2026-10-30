package com.z1812.drawsite.workspace;

import com.z1812.drawsite.application.*;
import com.z1812.drawsite.common.exception.BusinessException;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.time.*;
import java.util.*;

@Service
public class ProjectGitService {
    private final Path root;
    private final ApplicationMapper applications;
    private final RevisionMapper revisions;
    public ProjectGitService(@Value("${drawsite.workspace-root}") String root, ApplicationMapper applications, RevisionMapper revisions) {
        this.root = Path.of(root).toAbsolutePath().normalize(); this.applications = applications; this.revisions = revisions;
    }
    public record State(String head, String branch, List<String> changed, boolean clean) {}
    public record Commit(String hash, List<String> parents, String message, String author, Instant createdAt, boolean head) {}
    public record History(String head, String branch, List<Commit> commits, boolean truncated) {}
    private Git open(String app) throws Exception {
        Path path = root.resolve(UUID.fromString(app).toString());
        for (Path current = path; current != null; current = current.getParent()) if (Files.isSymbolicLink(current)) throw invalid("不允许符号链接工作区");
        if (Files.isSymbolicLink(path.resolve(".git")) || !Files.isDirectory(path.resolve(".git"))) throw invalid("项目Git仓库不存在");
        return Git.open(path.toFile());
    }
    public State status(String app) throws Exception {
        try (var git = open(app)) {
            var status = git.status().call();
            var paths = new TreeSet<String>(); paths.addAll(status.getUncommittedChanges()); paths.addAll(status.getUntracked());
            paths.removeIf(GitFilePolicy::system);
            return new State(git.getRepository().resolve("HEAD").name(), git.getRepository().getBranch(), List.copyOf(paths), paths.isEmpty());
        }
    }
    public History history(String app, int limit) throws Exception {
        try (var git = open(app)) {
            String head = git.getRepository().resolve("HEAD").name();
            var result = new ArrayList<Commit>();
            for (var commit : git.log().setMaxCount(limit + 1).call()) {
                result.add(new Commit(commit.name(), Arrays.stream(commit.getParents()).map(parent -> parent.name()).toList(),
                        commit.getFullMessage(), commit.getAuthorIdent().getName(), Instant.ofEpochSecond(commit.getCommitTime()), head.equals(commit.name())));
            }
            return new History(head, git.getRepository().getBranch(), List.copyOf(result.subList(0, Math.min(limit, result.size()))), result.size() > limit);
        }
    }
    public State commit(String app, String message, String expectedHead) throws Exception {
        return commit(app, message, expectedHead, null);
    }
    public State commit(String app, String message, String expectedHead, List<String> files) throws Exception {
        if (message == null || message.length() > 200 || !message.matches("(?s)(feat|fix|docs|refactor|test|chore)(\\([a-zA-Z0-9_.-]+\\))?: [^\\r\\n]+")) throw invalid("提交说明须为feat: 说明、fix: 说明等格式，最多200字符");
        try (var git = open(app)) {
            checkHead(git, expectedHead);
            stage(git, files);
            if (git.status().call().getAdded().isEmpty() && git.status().call().getChanged().isEmpty() && git.status().call().getRemoved().isEmpty()) throw invalid("没有可提交的修改");
            var commit = git.commit().setMessage(message).setAuthor("DrawSite", "drawsite@localhost").setCommitter("DrawSite", "drawsite@localhost").call();
            index(app, commit.name(), message);
        }
        return status(app);
    }
    private void stage(Git git) throws Exception {
        stage(git, null);
    }
    private void stage(Git git, List<String> selected) throws Exception {
        if (git.getRepository().getRepositoryState() != RepositoryState.SAFE) throw invalid("仓库状态异常，无法提交");
        var status = git.status().call();
        if (!status.getConflicting().isEmpty()) throw invalid("存在未解决的Git冲突");
        var paths = new TreeSet<String>(); paths.addAll(status.getUncommittedChanges()); paths.addAll(status.getUntracked());
        paths.removeIf(GitFilePolicy::system);
        if (selected != null) {
            if (selected.isEmpty() || selected.size() > 1000 || !paths.containsAll(selected)) throw invalid("请选择有效的未提交代码文件");
            paths.retainAll(selected);
        }
        // 清空暂存选择，保留所有工作区内容
        git.reset().setMode(ResetCommand.ResetType.MIXED).setRef("HEAD").call();
        untrackSystem(git);
        for (String path : paths) {
            validatePath(path);
            Path file = git.getRepository().getWorkTree().toPath().resolve(path);
            for (Path current = file; current != null && current.startsWith(git.getRepository().getWorkTree().toPath()); current = current.getParent()) {
                if (Files.isSymbolicLink(current)) throw invalid("不能提交符号链接");
            }
            if (Files.exists(file)) git.add().addFilepattern(path).call();
            else git.add().setUpdate(true).addFilepattern(path).call();
        }
        // 防止外部暂存文件绕过路径和记忆权限
        var cache = git.getRepository().readDirCache();
        for (int i = 0; i < cache.getEntryCount(); i++) {
            var entry = cache.getEntry(i); validatePath(entry.getPathString());
            if (entry.getFileMode().equals(FileMode.SYMLINK) || entry.getFileMode().equals(FileMode.GITLINK)) throw invalid("不支持符号链接或子模块");
        }
    }
    private void untrackSystem(Git git) throws Exception {
        var cache = git.getRepository().readDirCache();
        var paths = new ArrayList<String>();
        for (int i = 0; i < cache.getEntryCount(); i++) if (GitFilePolicy.system(cache.getEntry(i).getPathString())) paths.add(cache.getEntry(i).getPathString());
        for (String path : paths) git.rm().setCached(true).addFilepattern(path).call();
    }
    public State rollback(String app, String target, String expectedHead) throws Exception {
        if (target == null || !target.matches("[a-fA-F0-9]{40}")) throw invalid("需要完整目标提交哈希");
        try (var git = open(app); var walk = new RevWalk(git.getRepository())) {
            checkHead(git, expectedHead);
            var current = walk.parseCommit(git.getRepository().resolve("HEAD"));
            if (!git.getRepository().getRepositoryState().equals(RepositoryState.SAFE)) throw invalid("仓库状态异常，无法回滚");
            var destination = walk.parseCommit(ObjectId.fromString(target));
            if (!walk.isMergedInto(destination, current)) throw invalid("只能回滚当前历史中的版本");
            try (var tree = new TreeWalk(git.getRepository())) {
                tree.addTree(destination.getTree()); tree.setRecursive(true);
                while (tree.next()) {
                    validatePath(tree.getPathString());
                    if (tree.getFileMode(0).equals(FileMode.SYMLINK) || tree.getFileMode(0).equals(FileMode.GITLINK)) throw invalid("目标版本包含不支持的文件类型");
                }
            }
            // 回滚前将未提交内容保存为快照
            if (!status(app).clean()) {
                stage(git);
                var backup = git.commit().setMessage("chore: 回滚前保存工作区").setAllowEmpty(true).setAuthor("DrawSite", "drawsite@localhost").setCommitter("DrawSite", "drawsite@localhost").call();
                index(app, backup.name(), backup.getFullMessage());
            }
            String parent = git.getRepository().resolve("HEAD").name();
            try {
                restoreCode(git, destination.getTree());
                stage(git);
                var restored = git.commit().setAllowEmpty(true).setMessage("chore: 恢复版本 " + target.substring(0, 8)).setAuthor("DrawSite", "drawsite@localhost").setCommitter("DrawSite", "drawsite@localhost").call();
                index(app, restored.name(), restored.getFullMessage());
            } catch (Exception error) {
                restoreCode(git, walk.parseCommit(ObjectId.fromString(parent)).getTree());
                git.reset().setMode(ResetCommand.ResetType.MIXED).setRef(parent).call();
                throw error;
            }
        }
        return status(app);
    }
    private void restoreCode(Git git, ObjectId treeId) throws Exception {
        Path directory = git.getRepository().getWorkTree().toPath();
        var target = new HashMap<String, byte[]>();
        try (var tree = new TreeWalk(git.getRepository())) {
            tree.addTree(treeId); tree.setRecursive(true);
            while (tree.next()) {
                String path = tree.getPathString();
                if (GitFilePolicy.system(path)) continue;
                validatePath(path);
                if (!tree.getFileMode(0).equals(FileMode.REGULAR_FILE) && !tree.getFileMode(0).equals(FileMode.EXECUTABLE_FILE)) throw invalid("目标版本包含不支持的文件类型");
                target.put(path, git.getRepository().open(tree.getObjectId(0)).getBytes());
            }
        }
        var cache = git.getRepository().readDirCache();
        for (int i = 0; i < cache.getEntryCount(); i++) {
            String path = cache.getEntry(i).getPathString();
            if (!GitFilePolicy.system(path) && !target.containsKey(path)) Files.deleteIfExists(directory.resolve(path));
        }
        for (var entry : target.entrySet()) {
            Path file = directory.resolve(entry.getKey()).normalize();
            if (!file.startsWith(directory)) throw invalid("路径超出工作区");
            for (Path current = file; current != null && current.startsWith(directory); current = current.getParent()) if (Files.isSymbolicLink(current)) throw invalid("不允许符号链接");
            Files.createDirectories(file.getParent()); Files.write(file, entry.getValue());
        }
    }
    private void checkHead(Git git, String expected) throws Exception {
        if (expected == null || !git.getRepository().resolve("HEAD").name().equals(expected)) throw new BusinessException("GIT_CONFLICT", "版本已变化，请刷新后重试", HttpStatus.CONFLICT);
    }
    private void validatePath(String path) {
        for (String part : path.split("/")) if (part.equals("..") || Set.of(".git", ".idea", "node_modules", "target", "dist", ".drawsite").contains(part.toLowerCase(Locale.ROOT))
                || (part.toLowerCase(Locale.ROOT).startsWith(".env") && !part.equalsIgnoreCase(".env.example"))) throw invalid("版本包含受保护路径");
    }
    private void index(String app, String hash, String message) {
        var application = new ApplicationEntity(); application.setId(app); application.setCurrentCommit(hash); application.setUpdatedAt(OffsetDateTime.now()); applications.update(application);
        var revision = new RevisionEntity(); revision.setId(UUID.randomUUID().toString()); revision.setApplicationId(app); revision.setCommitHash(hash); revision.setSummary(message); revision.setCreatedAt(OffsetDateTime.now()); revisions.insert(revision);
    }
    private static BusinessException invalid(String message) { return new BusinessException("GIT_OPERATION_INVALID", message, HttpStatus.BAD_REQUEST); }
}
