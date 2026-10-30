package com.z1812.drawsite.docker;

import cn.dev33.satoken.stp.StpUtil;
import com.z1812.drawsite.common.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static com.z1812.drawsite.docker.DockerDtos.*;

@RestController
public class DockerController {
    private final ContainerService containers;
    private final InfrastructureSettings settings;
    private final DockerEngine engine;
    private final DeploymentService deployments;
    public DockerController(ContainerService containers, InfrastructureSettings settings, DockerEngine engine, DeploymentService deployments) {
        this.containers = containers; this.settings = settings; this.engine = engine; this.deployments = deployments;
    }
    private String owner() { return StpUtil.getLoginIdAsString(); }
    @GetMapping("/api/docker/templates") public ApiResponse<List<Template>> templates() { return ApiResponse.success(containers.templates(false)); }
    @GetMapping("/api/docker/containers") public ApiResponse<List<ContainerEntity>> list() { return ApiResponse.success(containers.list(owner(), false)); }
    @PostMapping("/api/docker/containers") public ApiResponse<ContainerEntity> create(@Valid @RequestBody Create request) { return ApiResponse.success(containers.create(request, owner())); }
    @PostMapping("/api/docker/containers/{id}/{action:start|stop}") public ApiResponse<ContainerEntity> action(@PathVariable String id, @PathVariable String action) { return ApiResponse.success(containers.action(id, action, owner(), false)); }
    @DeleteMapping("/api/docker/containers/{id}") public ApiResponse<Void> delete(@PathVariable String id) { containers.delete(id, owner(), false); return ApiResponse.success(null); }
    @GetMapping("/api/docker/containers/{id}/logs") public ApiResponse<String> logs(@PathVariable String id) { return ApiResponse.success(containers.logs(id, owner(), false)); }
    @PostMapping("/api/docker/containers/{id}/exec") public ApiResponse<DockerEngine.Result> execute(@PathVariable String id, @Valid @RequestBody Execute request) { return ApiResponse.success(containers.execute(id, request, owner(), false)); }
    @GetMapping("/api/admin/docker/config") public ApiResponse<ConfigView> config() { StpUtil.checkRole("ADMIN"); return ApiResponse.success(settings.view()); }
    @PutMapping("/api/admin/docker/config") public ApiResponse<ConfigView> configSave(@Valid @RequestBody Config request) { StpUtil.checkRole("ADMIN"); return ApiResponse.success(settings.save(request)); }
    @GetMapping("/api/admin/docker/status") public ApiResponse<String> status() { StpUtil.checkRole("ADMIN"); return ApiResponse.success(engine.checked(15, "info", "--format", "{{json .}}")); }
    @GetMapping("/api/admin/docker/templates") public ApiResponse<List<Template>> adminTemplates() { StpUtil.checkRole("ADMIN"); return ApiResponse.success(containers.templates(true)); }
    @PutMapping("/api/admin/docker/templates") public ApiResponse<Template> template(@Valid @RequestBody Template request) { StpUtil.checkRole("ADMIN"); return ApiResponse.success(containers.saveTemplate(request)); }
    @DeleteMapping("/api/admin/docker/templates/{id}") public ApiResponse<Void> templateDelete(@PathVariable String id) { StpUtil.checkRole("ADMIN"); containers.deleteTemplate(id); return ApiResponse.success(null); }
    @PostMapping("/api/admin/docker/templates/{id}/pull") public ApiResponse<String> pull(@PathVariable String id) {
        StpUtil.checkRole("ADMIN");
        var template = containers.templates(true).stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow();
        return ApiResponse.success(engine.checked(300, "pull", template.image()));
    }
    @GetMapping("/api/admin/docker/containers") public ApiResponse<List<ContainerEntity>> all() { StpUtil.checkRole("ADMIN"); return ApiResponse.success(containers.list(owner(), true)); }
    @PostMapping("/api/admin/docker/containers/{id}/{action:start|stop}") public ApiResponse<ContainerEntity> adminAction(@PathVariable String id, @PathVariable String action) { StpUtil.checkRole("ADMIN"); return ApiResponse.success(containers.action(id, action, owner(), true)); }
    @DeleteMapping("/api/admin/docker/containers/{id}") public ApiResponse<Void> adminDelete(@PathVariable String id) { StpUtil.checkRole("ADMIN"); containers.delete(id, owner(), true); return ApiResponse.success(null); }
    @GetMapping("/api/admin/docker/containers/{id}/logs") public ApiResponse<String> adminLogs(@PathVariable String id) { StpUtil.checkRole("ADMIN"); return ApiResponse.success(containers.logs(id, owner(), true)); }
    @GetMapping("/api/deployments") public ApiResponse<List<DeploymentEntity>> deployments() { return ApiResponse.success(deployments.list(owner(), false)); }
    @PostMapping("/api/deployments") public ApiResponse<DeploymentEntity> publish(@Valid @RequestBody Publish request) { return ApiResponse.success(deployments.publish(request, owner())); }
    @DeleteMapping("/api/deployments/{id}") public ApiResponse<Void> offline(@PathVariable String id) { deployments.offline(id, owner(), false); return ApiResponse.success(null); }
    @GetMapping("/api/admin/deployments") public ApiResponse<List<DeploymentEntity>> allDeployments() { StpUtil.checkRole("ADMIN"); return ApiResponse.success(deployments.list(owner(), true)); }
    @DeleteMapping("/api/admin/deployments/{id}") public ApiResponse<Void> adminOffline(@PathVariable String id) { StpUtil.checkRole("ADMIN"); deployments.offline(id, owner(), true); return ApiResponse.success(null); }
}
