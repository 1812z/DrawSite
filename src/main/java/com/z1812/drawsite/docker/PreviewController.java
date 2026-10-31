package com.z1812.drawsite.docker;

import com.z1812.drawsite.application.ApplicationService;
import com.z1812.drawsite.common.api.ApiResponse;
import cn.dev33.satoken.stp.StpUtil;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/applications/{applicationId}/preview")
public class PreviewController {
    private final ApplicationService applications;
    private final ContainerService containers;
    private final PreviewAccess access;
    private final java.nio.file.Path workspace;
    public PreviewController(ApplicationService applications, ContainerService containers, PreviewAccess access,
                             @org.springframework.beans.factory.annotation.Value("${drawsite.workspace-root}") String workspace) {
        this.applications = applications; this.containers = containers; this.access = access;
        this.workspace = java.nio.file.Path.of(workspace).toAbsolutePath().normalize();
    }
    public record StaticPreview(String directory) {}
    @PostMapping("/static")
    public ApiResponse<String> staticPreview(@PathVariable String applicationId, @RequestBody StaticPreview body, HttpServletResponse response) {
        applications.requireOwned(applicationId);
        var root = workspace.resolve(java.util.UUID.fromString(applicationId).toString());
        String directory = body.directory() == null || body.directory().isBlank() ? "." : body.directory();
        var target = root.resolve(directory).normalize();
        if (!target.startsWith(root)) throw new IllegalArgumentException("目录不能超出当前项目");
        for (var p = target; p != null; p = p.getParent()) if (java.nio.file.Files.isSymbolicLink(p)) throw new IllegalArgumentException("不能预览符号链接");
        if (!java.nio.file.Files.isRegularFile(target.resolve("index.html"), java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("静态目录需要index.html");
        access.grantSite(applicationId, target, response);
        return ApiResponse.success("/sites/" + applicationId + "/__preview/");
    }
    @PostMapping("/{containerId}")
    public ApiResponse<Void> authorize(@PathVariable String applicationId, @PathVariable String containerId, HttpServletResponse response) {
        applications.requireOwned(applicationId);
        var container = containers.require(containerId, StpUtil.getLoginIdAsString(), false);
        if (!applicationId.equals(container.getApplicationId()) || !"RUNNING".equals(container.getStatus())) throw new IllegalArgumentException("请选择当前项目运行中的容器");
        access.grant(containerId, response);
        return ApiResponse.success(null);
    }
}
