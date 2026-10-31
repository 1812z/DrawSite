package com.z1812.drawsite.workspace;

import com.z1812.drawsite.application.ApplicationService;
import cn.dev33.satoken.stp.StpUtil;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.*;

@RestController
public class ProjectDownloadController {
    private final ApplicationService applications;
    private final Path workspace;
    private final Map<String, AtomicBoolean> downloads = new ConcurrentHashMap<>();
    public ProjectDownloadController(ApplicationService applications, @Value("${drawsite.workspace-root}") String workspace) {
        this.applications = applications; this.workspace = Path.of(workspace).toAbsolutePath().normalize();
    }
    @GetMapping("/api/applications/{id}/download")
    public void download(@PathVariable String id, HttpServletResponse response) throws IOException {
        applications.requireOwned(id);
        Path root = workspace.resolve(UUID.fromString(id).toString());
        for (Path p = root; p != null; p = p.getParent()) if (Files.isSymbolicLink(p)) throw new IllegalArgumentException("项目目录不能为符号链接");
        if (!Files.isDirectory(root)) throw new IllegalArgumentException("项目目录不存在");
        String key = StpUtil.getLoginIdAsString() + ":" + id;
        var cancelled = new AtomicBoolean();
        var previous = downloads.put(key, cancelled);
        if (previous != null) previous.set(true);
        response.setContentType("application/zip");
        response.setHeader("Content-Disposition", "attachment; filename=\"project-" + id + ".zip\"");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Accel-Buffering", "no");
        try (var zip = new ZipOutputStream(response.getOutputStream())) {
            zip.setLevel(Deflater.BEST_SPEED);
            byte[] buffer = new byte[32768];
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                private void check() throws IOException {
                    if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("下载已取消");
                }
                private String name(Path file) { return root.relativize(file).toString().replace('\\', '/'); }
                @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    check();
                    if (!dir.equals(root)) { zip.putNextEntry(new ZipEntry(name(dir) + "/")); zip.closeEntry(); }
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    check();
                    if (!attrs.isRegularFile() || attrs.isSymbolicLink()) return FileVisitResult.CONTINUE;
                    zip.putNextEntry(new ZipEntry(name(file)));
                    try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                        int count;
                        while ((count = input.read(buffer)) != -1) { check(); zip.write(buffer, 0, count); }
                    }
                    zip.closeEntry(); return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException error) {
            // 下载断开或被新请求替代时终止，不向ZIP响应追加JSON
            if (!response.isCommitted() && !cancelled.get()) throw error;
        } finally { downloads.remove(key, cancelled); }
    }
}
