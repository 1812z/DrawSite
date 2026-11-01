package com.z1812.drawsite.application;

import com.z1812.drawsite.common.api.ApiResponse;
import com.z1812.drawsite.common.exception.BusinessException;
import com.z1812.drawsite.workspace.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
public class ProgressController {
    private final ApplicationService applications;
    private final WorkspaceService workspace;
    private record CachedProgress(String content, ProgressDocument.Summary summary) {}
    private final java.util.Map<String, CachedProgress> parsedCache = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<>(16, 0.75f, true));
    private ProgressDocument.Summary parse(String id, String content) {
        var cached = parsedCache.get(id);
        if (cached != null && cached.content().equals(content)) return cached.summary();
        var summary = ProgressDocument.parse(content);
        // 按内容复用解析结果，每次仍安全读取文件，外部修改及AI写入立即可见。
        synchronized (parsedCache) {
            parsedCache.put(id, new CachedProgress(content, summary));
            while (parsedCache.size() > 32) parsedCache.remove(parsedCache.keySet().iterator().next());
        }
        return summary;
    }
    public ProgressController(ApplicationService applications, WorkspaceService workspace) {
        this.applications = applications; this.workspace = workspace;
    }
    @GetMapping("/api/applications/{id}/progress")
    public ApiResponse<ProgressDocument.Summary> get(@PathVariable String id) throws java.io.IOException {
        applications.requireOwned(id);
        try {
            String content = workspace.readDocument(id, "progress.json");
            var result = parse(id, content);
            return ApiResponse.success(result);
        }
        catch (IllegalArgumentException error) { throw new BusinessException("PROGRESS_INVALID", error.getMessage(), HttpStatus.CONFLICT); }
    }
}
