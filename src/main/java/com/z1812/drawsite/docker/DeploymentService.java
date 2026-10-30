package com.z1812.drawsite.docker;

import com.z1812.drawsite.application.ApplicationMapper;
import com.z1812.drawsite.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.OffsetDateTime;
import java.util.*;

@Service
public class DeploymentService {
    private final DeploymentMapper mapper;
    private final ContainerService containers;
    private final ApplicationMapper applications;
    private final DockerEngine docker;
    private final TransactionTemplate tx;
    private final Path workspace;
    private final Path sites;
    public DeploymentService(DeploymentMapper mapper, ContainerService containers, ApplicationMapper applications,
                             DockerEngine docker, TransactionTemplate tx,
                             @Value("${drawsite.workspace-root}") String workspace,
                             @Value("${drawsite.sites-directory:./data/sites}") String sites) {
        this.mapper = mapper; this.containers = containers; this.applications = applications;
        this.docker = docker; this.tx = tx;
        this.workspace = Path.of(workspace).toAbsolutePath().normalize();
        this.sites = Path.of(sites).toAbsolutePath().normalize();
    }
    public List<DeploymentEntity> list(String owner, boolean admin) { return admin ? mapper.all() : mapper.owned(owner); }
    public DeploymentEntity publish(DockerDtos.Publish request, String owner) {
        if (!Set.of("STATIC", "SERVICE").contains(request.type())) throw invalid("无效发布类型");
        return tx.execute(status -> {
            var app = applications.lockOwned(request.applicationId(), owner);
            if (app == null || !"READY".equals(app.getStatus())) throw invalid("项目不存在或未就绪");
            var item = new DeploymentEntity();
            item.setId(UUID.randomUUID().toString()); item.setApplicationId(request.applicationId());
            item.setOwnerUserId(owner); item.setType(request.type()); item.setStatus("ONLINE"); item.setCreatedAt(OffsetDateTime.now());
            if ("STATIC".equals(request.type())) {
                var previous = mapper.staticForApplication(request.applicationId());
                item.setDirectory(request.directory() == null || request.directory().isBlank() ? "." : request.directory());
                item.setUrl("/sites/" + request.applicationId() + "/");
                Path target = sites.resolve(item.getId());
                try {
                    copySite(request.applicationId(), request.directory(), target);
                    if (previous != null) mapper.deleteById(previous.getId());
                    mapper.insert(item);
                    org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                            new org.springframework.transaction.support.TransactionSynchronization() {
                                @Override public void afterCompletion(int state) {
                                    if (state != STATUS_COMMITTED) removeTree(target);
                                    else if (previous != null) removeTree(sites.resolve(previous.getId()));
                                }
                            });
                    return item;
                } catch (Exception error) {
                    removeTree(target);
                    if (error instanceof RuntimeException runtime) throw runtime;
                    throw invalid("静态文件发布失败，请检查发布目录");
                }
            }
            var container = containers.require(request.containerId(), owner, false);
            if (!request.applicationId().equals(container.getApplicationId())) throw invalid("容器不属于当前项目");
            if (!"PUBLISH".equals(container.getPurpose()) || !"RUNNING".equals(container.getStatus())) throw invalid("请选择正在运行的常驻服务容器");
            if (container.getHostPort() == null || container.getSecondaryHostPort() == null) throw invalid("容器缺少端口映射，请重新创建");
            if (mapper.forContainer(container.getId()) != null) throw invalid("容器已经上线");
            if (!"true".equals(docker.checked(10, "inspect", "--format", "{{.State.Running}}", container.getDockerId()))) throw invalid("容器实际未运行");
            var template = containers.templates(true).stream().filter(t -> t.id().equals(container.getTemplateId())).findFirst().orElseThrow(() -> invalid("模板已删除"));
            String healthUrl = "'http://127.0.0.1:" + container.getInternalPort() + template.healthPath().replace("'", "'\\''") + "'";
            docker.checked(15, "exec", container.getDockerId(), "sh", "-lc",
                    "if command -v curl >/dev/null 2>&1; then curl --fail --silent --max-time 5 " + healthUrl
                    + " >/dev/null; elif command -v wget >/dev/null 2>&1; then wget -q -T 5 -O /dev/null " + healthUrl
                    + "; else echo '镜像需要curl或wget进行健康检查' >&2; exit 127; fi");
            item.setContainerId(container.getId()); item.setUrl("/services/" + container.getId() + "/1/");
            mapper.insert(item); return item;
        });
    }
    private void copySite(String applicationId, String directory, Path target) throws IOException {
        Path root = workspace.resolve(UUID.fromString(applicationId).toString());
        if (directory == null || directory.isBlank()) directory = ".";
        Path source = root.resolve(directory).normalize();
        if (!source.startsWith(root)) throw invalid("发布目录必须位于当前项目内");
        for (Path p = source; p != null; p = p.getParent()) if (Files.isSymbolicLink(p)) throw invalid("发布目录不能包含符号链接");
        if (!Files.isDirectory(source) || !Files.isRegularFile(source.resolve("index.html"), LinkOption.NOFOLLOW_LINKS)) throw invalid("发布目录必须包含index.html，构建项目请先生成dist等产物目录");
        Files.createDirectories(sites);
        if (sites.startsWith(source)) throw invalid("站点存储目录不能位于发布源目录内");
        for (Path p = sites; p != null; p = p.getParent()) if (Files.isSymbolicLink(p)) throw invalid("站点存储目录不能包含符号链接");
        Files.createDirectory(target);
        long[] bytes = {0}; int[] files = {0};
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (!dir.equals(source) && excluded(dir.getFileName().toString())) return FileVisitResult.SKIP_SUBTREE;
                Files.createDirectories(target.resolve(source.relativize(dir))); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (attrs.isSymbolicLink() || !attrs.isRegularFile()) throw invalid("发布目录不能包含链接或特殊文件");
                if (excluded(file.getFileName().toString())) return FileVisitResult.CONTINUE;
                bytes[0] += attrs.size();
                if (++files[0] > 20000 || bytes[0] > 512L * 1024 * 1024) throw invalid("静态站点最多20000个文件、512MiB");
                Files.copy(file, target.resolve(source.relativize(file))); return FileVisitResult.CONTINUE;
            }
        });
    }
    private boolean excluded(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.startsWith(".") || Set.of("node_modules", "agents.md", "progress.json", "dockerfile", "compose.yml", "compose.yaml").contains(lower)
                || lower.endsWith(".pem") || lower.endsWith(".key") || lower.endsWith(".java") || lower.endsWith(".sql");
    }
    public void offline(String id, String owner, boolean admin) {
        tx.executeWithoutResult(status -> {
            var item = mapper.selectOneById(id);
            if (item == null || (!admin && !owner.equals(item.getOwnerUserId()))) throw invalid("部署不存在");
            applications.lockById(item.getApplicationId());
            mapper.deleteById(id);
            if ("STATIC".equals(item.getType())) org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override public void afterCommit() { removeTree(sites.resolve(item.getId())); }
                    });
        });
    }
    public Path siteRoot(String applicationId) {
        var item = mapper.staticForApplication(applicationId);
        if (item == null || !"ONLINE".equals(item.getStatus())) throw new BusinessException("SITE_NOT_FOUND", "站点不存在或已下线", HttpStatus.NOT_FOUND);
        return sites.resolve(item.getId());
    }
    private void removeTree(Path root) {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException ignored) { }
    }
    private static BusinessException invalid(String message) { return new BusinessException("DEPLOYMENT_INVALID", message, HttpStatus.BAD_REQUEST); }
}
