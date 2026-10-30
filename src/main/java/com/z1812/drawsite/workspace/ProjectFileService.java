package com.z1812.drawsite.workspace;

import com.github.difflib.UnifiedDiffUtils;
import com.z1812.drawsite.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

@Service
public class ProjectFileService {
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final Set<String> HIDDEN = Set.of(".git", ".idea", "node_modules", "target", "dist", ".drawsite");
    private final Path root;

    public record Entry(String path, String type) {}
    public record Listing(List<Entry> entries, boolean truncated) {}
    public record FileContent(String path, String content, String sha256, int totalLines, int startLine, boolean truncated) {}
    public record Change(String path, String operation, String sha256) {}
    public record Match(String path, int line, String text) {}
    public record SearchResult(List<Match> matches, boolean truncated) {}

    public ProjectFileService(@Value("${drawsite.workspace-root}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    public Listing list(String appId, String directory, int depth) throws IOException {
        if (depth < 1 || depth > 5) throw invalid("目录深度必须为1至5");
        Path base = resolve(appId, directory, true);
        if (!Files.isDirectory(base)) throw invalid("目录不存在");
        var entries = new ArrayList<Entry>();
        var truncated = new boolean[1];
        Files.walkFileTree(base, EnumSet.noneOf(FileVisitOption.class), depth, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, java.nio.file.attribute.BasicFileAttributes attrs) {
                if (!dir.equals(base) && blocked(dir.getFileName().toString())) return FileVisitResult.SKIP_SUBTREE;
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) {
                if (Files.isSymbolicLink(file) || blocked(file.getFileName().toString())) return FileVisitResult.CONTINUE;
                if (entries.size() >= 1000) { truncated[0] = true; return FileVisitResult.TERMINATE; }
                entries.add(new Entry(relative(appId, file), attrs.isDirectory() ? "DIRECTORY" : "FILE"));
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException exception) throws IOException {
                if (exception != null) throw exception;
                if (!dir.equals(base)) {
                    if (entries.size() >= 1000) { truncated[0] = true; return FileVisitResult.TERMINATE; }
                    entries.add(new Entry(relative(appId, dir), "DIRECTORY"));
                }
                return FileVisitResult.CONTINUE;
            }
        });
        entries.sort(Comparator.comparing(Entry::path));
        return new Listing(entries, truncated[0]);
    }

    public FileContent read(String appId, String path, int startLine, int lineCount) throws IOException {
        if (startLine < 1 || lineCount < 1 || lineCount > 500) throw invalid("读取范围不合法，最多500行");
        String content = text(resolve(appId, path, false));
        String[] lines = content.split("\n", -1);
        int start = Math.min(startLine - 1, lines.length);
        int end = Math.min(start + lineCount, lines.length);
        String slice = String.join("\n", Arrays.copyOfRange(lines, start, end));
        boolean truncated = end < lines.length;
        if (slice.length() > 40000) { slice = slice.substring(0, 40000); truncated = true; }
        return new FileContent(path, slice, hash(content), lines.length, startLine, truncated);
    }

    public String readText(String appId, String path) throws IOException { return text(resolve(appId, path, false)); }
    public boolean exists(String appId, String path) throws IOException { return Files.isRegularFile(resolve(appId, path, false), LinkOption.NOFOLLOW_LINKS); }

    public SearchResult search(String appId, String directory, String query) throws IOException {
        if (query == null || query.isBlank() || query.length() > 200) throw invalid("搜索文本不能为空且最多200字符");
        var listing = list(appId, directory, 5);
        var found = new ArrayList<Match>();
        int scanned = 0;
        for (var entry : listing.entries()) {
            if (!"FILE".equals(entry.type())) continue;
            Path file = resolve(appId, entry.path(), false);
            if (Files.size(file) > MAX_BYTES) continue;
            if (++scanned > 200) return new SearchResult(found, true);
            String content;
            try { content = text(file); }
            catch (BusinessException | java.nio.charset.MalformedInputException exception) { continue; }
            String[] lines = content.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (!lines[i].contains(query)) continue;
                if (found.size() >= 100) return new SearchResult(found, true);
                found.add(new Match(entry.path(), i + 1, lines[i].substring(0, Math.min(lines[i].length(), 500))));
            }
        }
        return new SearchResult(found, listing.truncated());
    }

    public Change write(String appId, String path, String content, String expectedHash) throws IOException {
        Path file = resolve(appId, path, false);
        checkVersion(file, expectedHash);
        save(file, content);
        return new Change(path, "WRITE", hash(content));
    }

    public Change writeBytes(String appId, String path, byte[] content, String expectedHash) throws IOException {
        if (content == null || content.length > MAX_BYTES) throw invalid("资源超过2MB");
        Path file = resolve(appId, path, false);
        if (file.getFileName().toString().equals("openapi.json") || file.getFileName().toString().endsWith(".test.json")) {
            return write(appId, path, new String(content, StandardCharsets.UTF_8), expectedHash);
        }
        if (Set.of("agents.md", "progress.json").contains(file.getFileName().toString().toLowerCase(Locale.ROOT))) throw invalid("项目记忆和进度只允许文本编辑");
        if ("NEW".equals(expectedHash)) {
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) throw invalid("文件已存在");
        } else {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_BYTES
                    || !hashBytes(Files.readAllBytes(file)).equals(expectedHash)) throw new BusinessException("FILE_CONFLICT", "文件已变化，请重新读取后修改", HttpStatus.CONFLICT);
        }
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), ".drawsite-", ".tmp");
        try {
            Files.write(temporary, content);
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException error) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
        return new Change(path, "DOWNLOAD", hashBytes(content));
    }
    private String hashBytes(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    public Change patch(String appId, String path, String diff, String expectedHash) throws Exception {
        Path file = resolve(appId, path, false);
        checkVersion(file, expectedHash);
        String original = text(file);
        if (diff == null || diff.length() > MAX_BYTES || !diff.startsWith("--- ") || !diff.contains("\n+++ ") || !diff.contains("\n@@ ")) {
            throw invalid("需要包含文件头和行号的单文件 unified diff");
        }
        var lines = Arrays.asList(diff.replace("\r\n", "\n").split("\n", -1));
        for (int i = 2; i < lines.size(); i++) {
            if (lines.get(i).startsWith("--- ") || lines.get(i).startsWith("+++ ")) throw invalid("每次 patch 只允许一个文件");
            if (lines.get(i).startsWith("\\ No newline")) throw invalid("请使用文本替换工具处理末尾无换行的文件");
        }
        if (original.contains("\r\n")) throw invalid("CRLF 文件请使用精确文本替换，避免改变换行格式");
        boolean trailing = original.endsWith("\n");
        String body = trailing ? original.substring(0, original.length() - 1) : original;
        List<String> source = body.isEmpty() ? new ArrayList<>() : new ArrayList<>(Arrays.asList(body.split("\n", -1)));
        var parsed = UnifiedDiffUtils.parseUnifiedDiff(lines);
        if (parsed.getDeltas().isEmpty()) throw invalid("patch 不包含修改");
        String changed = String.join("\n", parsed.applyTo(source));
        if (trailing && !changed.isEmpty()) changed += "\n";
        save(file, changed);
        return new Change(path, "PATCH", hash(changed));
    }

    public Change replace(String appId, String path, String oldText, String newText, String expectedHash) throws IOException {
        Path file = resolve(appId, path, false);
        checkVersion(file, expectedHash);
        String original = text(file);
        if (oldText == null || oldText.isEmpty() || newText == null) throw invalid("替换内容不合法");
        int at = original.indexOf(oldText);
        if (at < 0 || original.indexOf(oldText, at + 1) >= 0) throw invalid("原文本必须精确匹配一次，请增加上下文");
        String changed = original.substring(0, at) + newText + original.substring(at + oldText.length());
        save(file, changed);
        return new Change(path, "REPLACE", hash(changed));
    }

    public Change delete(String appId, String path, String expectedHash) throws IOException {
        Path file = resolve(appId, path, false);
        checkVersion(file, expectedHash);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw invalid("只能删除普通文件");
        Files.delete(file);
        return new Change(path, "DELETE", null);
    }

    public Change move(String appId, String source, String target, String expectedHash) throws IOException {
        Path from = resolve(appId, source, false);
        Path to = resolve(appId, target, false);
        checkVersion(from, expectedHash);
        if (!Files.isRegularFile(from, LinkOption.NOFOLLOW_LINKS) || Files.exists(to, LinkOption.NOFOLLOW_LINKS)) throw invalid("源文件无效或目标已存在");
        String hash = hash(text(from));
        Files.createDirectories(to.getParent());
        resolve(appId, target, false);
        Files.move(from, to);
        return new Change(target, "MOVE", hash);
    }

    public Change mkdir(String appId, String path) throws IOException {
        Path directory = resolve(appId, path, false);
        Files.createDirectories(directory);
        return new Change(path, "MKDIR", null);
    }

    private Path resolve(String appId, String value, boolean allowRoot) throws IOException {
        Path project = root.resolve(UUID.fromString(appId).toString());
        if (value == null || value.length() > 500 || value.indexOf('\\') >= 0 || value.indexOf(':') >= 0
                || value.matches(".*[\\x00-\\x1F<>\"|?*].*")) throw invalid("使用项目相对路径和正斜杠");
        if (value.isBlank() && !allowRoot) throw invalid("路径不能为空");
        for (String part : value.split("/", -1)) {
            if (part.equals("..") || blocked(part) || part.endsWith(".") || part.endsWith(" ")
                    || part.toUpperCase(Locale.ROOT).matches("(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?")) throw invalid("路径不允许访问");
        }
        Path file = project.resolve(value.isBlank() ? "." : value).normalize();
        if (Path.of(value.isBlank() ? "." : value).isAbsolute() || !file.startsWith(project) || (!allowRoot && file.equals(project))) throw invalid("路径超出工作区");
        for (Path current = file; current != null; current = current.getParent()) {
            if (Files.isSymbolicLink(current)) throw invalid("不允许访问符号链接");
        }
        if (!Files.isDirectory(project, LinkOption.NOFOLLOW_LINKS)) throw invalid("工作区不存在");
        return file;
    }

    private boolean blocked(String part) {
        if (FileAccessScope.hidden(part)) return true;
        String name = part.toLowerCase(Locale.ROOT);
        return HIDDEN.contains(name) || (name.startsWith(".env") && !name.equals(".env.example"));
    }

    private String relative(String appId, Path file) { return root.resolve(appId).relativize(file).toString().replace('\\', '/'); }

    private String text(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw invalid("文件不存在或不是普通文件");
        if (Files.size(file) > MAX_BYTES) throw invalid("单文件不能超过2MB");
        String content = Files.readString(file, StandardCharsets.UTF_8);
        if (content.indexOf('\0') >= 0) throw invalid("不支持二进制文件");
        return content;
    }

    private void checkVersion(Path file, String expected) throws IOException {
        if (expected == null || expected.isBlank()) throw invalid("必须提供 sha256，新建文件使用 NEW");
        if ("NEW".equals(expected)) {
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) throw invalid("文件已存在");
        } else if (!hash(text(file)).equals(expected)) {
            throw new BusinessException("FILE_CONFLICT", "文件已变化，请重新读取后修改", HttpStatus.CONFLICT);
        }
    }

    private void save(Path file, String content) throws IOException {
        if (file.getFileName().toString().equalsIgnoreCase("progress.json")) ProgressDocument.parse(content);
        if (file.getFileName().toString().equals("openapi.json")) com.z1812.drawsite.testing.ApiDocuments.contract(content);
        if (file.getFileName().toString().endsWith(".test.json")) {
            Path contract = file.getParent().getParent().resolve("openapi.json");
            if (!Files.isRegularFile(contract, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(contract)) throw invalid("请先创建API契约");
            com.z1812.drawsite.testing.ApiDocuments.suite(content, com.z1812.drawsite.testing.ApiDocuments.contract(text(contract)));
        }
        if (content == null || content.indexOf('\0') >= 0 || content.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw invalid("文本不合法或超过2MB");
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), ".drawsite-", ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException exception) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }

    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private BusinessException invalid(String message) { return new BusinessException("FILE_OPERATION_INVALID", message, HttpStatus.BAD_REQUEST); }
}
