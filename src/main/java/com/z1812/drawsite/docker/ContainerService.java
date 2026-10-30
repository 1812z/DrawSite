package com.z1812.drawsite.docker;

import cn.dev33.satoken.stp.StpUtil;
import com.z1812.drawsite.application.*;
import com.z1812.drawsite.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import jakarta.annotation.PreDestroy;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static com.z1812.drawsite.docker.DockerDtos.*;

@Service
public class ContainerService {
    private final ContainerMapper mapper;
    private final DeploymentMapper deployments;
    private final InfrastructureSettings settings;
    private final ApplicationMapper applications;
    private final DockerEngine docker;
    private final ContainerNetworks networks;
    private final TransactionTemplate tx;
    private final Path workspace;
    private final JsonMapper json = JsonMapper.builder().build();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    public ContainerService(ContainerMapper mapper, DeploymentMapper deployments, InfrastructureSettings settings,
                            ApplicationMapper applications, DockerEngine docker, ContainerNetworks networks, TransactionTemplate tx,
                            @Value("${drawsite.workspace-root}") String workspace) {
        this.mapper = mapper; this.deployments = deployments; this.settings = settings; this.applications = applications;
        this.docker = docker; this.networks = networks; this.tx = tx; this.workspace = Path.of(workspace).toAbsolutePath().normalize();
        timer.scheduleWithFixedDelay(this::reconcile, 30, 30, TimeUnit.SECONDS);
    }
    public List<Template> templates(boolean admin) {
        return mapper.templates().stream().map(value -> json.readValue(value, Template.class)).filter(template -> admin || template.enabled()).toList();
    }
    public Template saveTemplate(Template template) {
        if (!template.healthPath().startsWith("/") || template.healthPath().contains("\n")) throw invalid("健康检查路径必须以/开头");
        mapper.templateSave(template.id(), json.writeValueAsString(template)); return template;
    }
    public void deleteTemplate(String id) { mapper.templateDelete(id); }
    public ContainerEntity require(String id, String owner, boolean admin) {
        var item = mapper.selectOneById(id);
        if (item == null || (!admin && !owner.equals(item.getOwnerUserId()))) throw new BusinessException("CONTAINER_NOT_FOUND", "容器不存在", HttpStatus.NOT_FOUND);
        return item;
    }
    public ContainerEntity create(Create request, String owner) {
        final String[] externalId = {null};
        final String[] networkId = {null};
        try {
            return tx.execute(status -> {
                var app = applications.lockOwned(request.applicationId(), owner);
                if (app == null || !"READY".equals(app.getStatus())) throw invalid("项目不存在或未就绪");
                var config = settings.lock();
                checkQuota(owner, config, true);
                var template = templates(false).stream().filter(t -> t.id().equals(request.templateId())).findFirst().orElseThrow(() -> invalid("模板不存在或已停用"));
                Path directory = workspace.resolve(UUID.fromString(request.applicationId()).toString());
                for (Path p = directory; p != null; p = p.getParent()) if (Files.isSymbolicLink(p)) throw invalid("工作区不能为符号链接");
                if (!Files.isDirectory(directory)) throw invalid("工作区不存在");
                if (directory.toString().contains(",")) throw invalid("工作区路径不能包含逗号");
                if (!"linux".equals(docker.checked(15, "info", "--format", "{{.OSType}}"))) throw invalid("仅支持Linux容器引擎");
                var image = docker.run(10, "image", "inspect", template.image());
                if (image.exitCode() != 0) throw invalid("镜像未安装，请管理员先拉取镜像");
                var item = new ContainerEntity();
                item.setId(UUID.randomUUID().toString()); item.setOwnerUserId(owner); item.setApplicationId(request.applicationId());
                String network = networks.create(item.getApplicationId()); networkId[0] = item.getApplicationId();
                item.setTemplateId(template.id()); item.setName("drawsite-" + item.getId()); item.setPurpose(request.purpose());
                item.setInternalPort(template.internalPort()); item.setStatus("CREATING"); item.setCreatedAt(OffsetDateTime.now());
                item.setPublicHost(config.publicHost());
                var reserved = new HashSet<Integer>();
                for (var existing : mapper.all()) {
                    if (existing.getHostPort() != null) reserved.add(existing.getHostPort());
                    if (existing.getSecondaryHostPort() != null) reserved.add(existing.getSecondaryHostPort());
                }
                String bindings = docker.checked(15, "ps", "-a", "--format", "{{.Ports}}");
                var ports = java.util.regex.Pattern.compile(":(\\d+)->").matcher(bindings);
                while (ports.find()) reserved.add(Integer.parseInt(ports.group(1)));
                for (int port = config.portStart(); port <= config.portEnd(); port++) {
                    if (reserved.contains(port)) continue;
                    if (item.getHostPort() == null) item.setHostPort(port);
                    else { item.setSecondaryHostPort(port); break; }
                }
                if (item.getSecondaryHostPort() == null) throw invalid("可分配端口不足，请联系管理员");
                if ("TEMPORARY".equals(request.purpose())) item.setExpiresAt(OffsetDateTime.now().plusMinutes(config.temporaryMinutes()));
                var args = new ArrayList<>(List.of("create", "--name", item.getName(), "--label", "drawsite.managed=true", "--label", "drawsite.id=" + item.getId(),
                        "--label", "drawsite.owner=" + owner, "--network", network, "--memory", config.memoryMiB() + "m", "--memory-swap", config.memoryMiB() + "m",
                        "--cpus", Double.toString(config.cpus()), "--pids-limit", Integer.toString(config.pids()),
                        "--storage-opt", "size=" + config.diskMiB() + "M", "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
                        "--log-opt", "max-size=10m", "--log-opt", "max-file=2", "--workdir", "/workspace",
                        "--mount", "type=bind,src=" + directory + ",dst=/workspace",
                        "--publish", item.getHostPort() + ":" + item.getInternalPort(),
                        "--publish", item.getSecondaryHostPort() + ":" + item.getSecondaryInternalPort(),
                        "--restart", "PUBLISH".equals(request.purpose()) ? "unless-stopped" : "no", template.image()));
                args.addAll(template.command());
                String id = docker.checked(30, args.toArray(String[]::new)); externalId[0] = id;
                item.setDockerId(id); mapper.insert(item);
                docker.checked(30, "start", id); item.setStatus("RUNNING"); mapper.update(item);
                return item;
            });
        } catch (RuntimeException error) {
            if (externalId[0] != null) try { docker.run(15, "rm", "-f", externalId[0]); } catch (Exception ignored) { }
            if (networkId[0] != null) try { networks.remove(networkId[0]); } catch (Exception ignored) { }
            throw error;
        }
    }
    private void checkQuota(String owner, Config config, boolean creating) {
        var all = mapper.all();
        long total = all.stream().filter(c -> owner.equals(c.getOwnerUserId())).count();
        long running = all.stream().filter(c -> owner.equals(c.getOwnerUserId()) && Set.of("RUNNING", "CREATING").contains(c.getStatus())).count();
        long platform = all.stream().filter(c -> Set.of("RUNNING", "CREATING").contains(c.getStatus())).count();
        if ((creating && total >= config.maxContainers()) || running >= config.maxRunning() || platform >= config.maxPlatformRunning()) throw new BusinessException("CONTAINER_QUOTA", "容器数量已达到配额，请先停止或删除其他容器", HttpStatus.CONFLICT);
    }
    public List<ContainerEntity> list(String owner, boolean admin) { return admin ? mapper.all() : mapper.owned(owner); }
    public ContainerEntity action(String id, String action, String owner, boolean admin) {
        var item = require(id, owner, admin);
            return tx.execute(status -> {
            var app = applications.lockById(item.getApplicationId());
            var config = settings.lock();
            var locked = mapper.lock(id);
            if (app == null || locked == null) throw invalid("容器已删除");
            if ("start".equals(action)) {
                if ("RUNNING".equals(locked.getStatus())) return locked;
                checkQuota(locked.getOwnerUserId(), config, false);
                docker.checked(30, "start", locked.getDockerId()); locked.setStatus("RUNNING");
                if ("TEMPORARY".equals(locked.getPurpose())) locked.setExpiresAt(OffsetDateTime.now().plusMinutes(config.temporaryMinutes()));
            } else if ("stop".equals(action)) {
                if (deployments.forContainer(id) != null) throw invalid("请先下线部署");
                docker.checked(30, "stop", "-t", "5", locked.getDockerId()); locked.setStatus("STOPPED");
            } else throw invalid("无效容器操作");
            mapper.update(locked); return locked;
        });
    }
    public void delete(String id, String owner, boolean admin) {
        var item = require(id, owner, admin);
        tx.executeWithoutResult(status -> {
            applications.lockById(item.getApplicationId()); settings.lock(); mapper.lock(id);
            if (deployments.forContainer(id) != null) throw invalid("请先下线部署再删除容器");
            var result = docker.run(30, "rm", "-f", item.getDockerId());
            if (result.exitCode() != 0 && !result.output().contains("No such container")) throw invalid("容器删除失败");
            mapper.deleteById(id);
            networks.remove(item.getApplicationId());
        });
    }
    public String logs(String id, String owner, boolean admin) { return docker.checked(15, "logs", "--tail", "300", require(id, owner, admin).getDockerId()); }
    public DockerEngine.Result execute(String id, Execute request, String owner, boolean admin) {
        var item = require(id, owner, admin);
        if (!"RUNNING".equals(item.getStatus())) throw invalid("容器未运行");
        String timeout = Integer.toString(request.timeoutSeconds());
        return docker.run(request.timeoutSeconds() + 10, "exec", item.getDockerId(), "timeout", "-k", "3", timeout, "sh", "-lc", request.command());
    }
    private void reconcile() {
        try {
            for (var item : mapper.all()) {
                try {
                    if ("TEMPORARY".equals(item.getPurpose()) && item.getExpiresAt() != null && item.getExpiresAt().isBefore(OffsetDateTime.now())) {
                        if ("RUNNING".equals(item.getStatus())) action(item.getId(), "stop", item.getOwnerUserId(), true);
                        continue;
                    }
                    var result = docker.run(10, "inspect", "--format", "{{.State.Status}}", item.getDockerId());
                    if (result.exitCode() == 0) {
                        String state = result.output().equals("running") ? "RUNNING" : result.output().equals("restarting") ? "RUNNING" : "STOPPED";
                        if (!state.equals(item.getStatus())) { item.setStatus(state); mapper.update(item); }
                    }
                } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
    }
    @org.springframework.context.event.EventListener
    public void deleting(com.z1812.drawsite.agent.runtime.StopAgentEvent event) {
        if (event.conversationId() != null) return;
        if (deployments.all().stream().anyMatch(d -> event.applicationId().equals(d.getApplicationId()))) throw invalid("请先下线已有部署，再删除项目");
        for (var item : mapper.all()) if (event.applicationId().equals(item.getApplicationId())) {
            if (deployments.forContainer(item.getId()) != null) throw invalid("请先下线部署并删除容器，再删除项目");
            delete(item.getId(), item.getOwnerUserId(), true);
        }
    }
    @PreDestroy public void close() { timer.shutdownNow(); }
    private static BusinessException invalid(String message) { return new BusinessException("CONTAINER_INVALID", message, HttpStatus.BAD_REQUEST); }
}
