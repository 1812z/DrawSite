package com.z1812.drawsite.tool.builtin;

import com.z1812.drawsite.docker.*;
import com.z1812.drawsite.tool.framework.*;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

@Component
public class DockerToolModule implements ProjectToolModule {
    private final ContainerService containers;
    public DockerToolModule(ContainerService containers) { this.containers = containers; }
    public String id() { return "docker"; }
    public String name() { return "容器运行"; }
    public String description() { return "使用模板创建受限容器，执行编译和测试命令"; }
    public boolean available(ToolContext context) { return context.memoryEnabled(); }
    public Object bind(ToolContext context) { return new Tools(containers, context); }
    public record Tools(ContainerService containers, ToolContext context) {
        @Tool("查询管理员已启用的容器模板，使用前必须查看image、command及instructions使用说明，按说明区分静态构建与常驻服务。静态构建使用TEMPORARY容器，构建后发布产物，不把Vite开发服务器作为正式上线服务")
        public Object listContainerTemplates() { return containers.templates(false); }
        @Tool("查询当前项目容器及端口。hostPort映射internalPort，secondaryHostPort映射secondaryInternalPort；公网地址为publicUrl/secondaryPublicUrl。同项目容器测试使用internalUrl。上线后浏览器推荐使用/services/{容器ID}/1/及/2/同域HTTPS代理，避免混合内容和跨域")
        public Object listProjectContainers() { return containers.list(context.ownerUserId(), false).stream().filter(c -> c.getApplicationId().equals(context.applicationId())).toList(); }
        @Tool("按模板创建当前项目容器，purpose仅TEMPORARY或PUBLISH；自动分配两个大于40000的宿主端口，返回hostPort/internalPort、secondaryHostPort/secondaryInternalPort及公网URL。服务必须监听0.0.0.0和返回的容器端口，不要自行选择宿主端口。纯静态HTML无需容器")
        public Object createProjectContainer(String templateId, String purpose) {
            if (!java.util.Set.of("TEMPORARY", "PUBLISH").contains(purpose)) throw new IllegalArgumentException("purpose必须为TEMPORARY或PUBLISH");
            return (DeferredToolResult) bound -> {
                if (bound.stopped().getAsBoolean()) throw new IllegalStateException("任务已停止");
                return containers.create(new DockerDtos.Create(bound.applicationId(), templateId, purpose), bound.ownerUserId());
            };
        }
        private void owned(String id) {
            var item = containers.require(id, context.ownerUserId(), false);
            if (!item.getApplicationId().equals(context.applicationId())) throw new IllegalArgumentException("只能操作当前项目的容器");
        }
        @Tool("在当前项目容器内执行Shell命令，timeoutSeconds为1至120，返回真实退出码及日志；镜像需要sh和timeout")
        public Object executeContainerCommand(String containerId, String command, int timeoutSeconds) {
            owned(containerId);
            if (timeoutSeconds < 1 || timeoutSeconds > 120 || command == null || command.isBlank() || command.length() > 20000) throw new IllegalArgumentException("命令或超时不合法");
            return (DeferredToolResult) bound -> {
                if (bound.stopped().getAsBoolean()) throw new IllegalStateException("任务已停止");
                return containers.execute(containerId, new DockerDtos.Execute(command, timeoutSeconds), bound.ownerUserId(), false);
            };
        }
        @Tool("读取当前项目容器最近300行日志")
        public Object readContainerLogs(String containerId) { owned(containerId); return (DeferredToolResult) bound -> containers.logs(containerId, bound.ownerUserId(), false); }
        @Tool("启动当前项目已有的已停止容器，优先复用而不是重复创建。临时容器到期后也可启动，并重新计算到期时间；仍遵守运行配额。启动只执行模板命令，应用服务是否就绪需另行检查，sleep infinity模板需自行启动应用进程")
        public Object startProjectContainer(String containerId) {
            owned(containerId);
            return (DeferredToolResult) bound -> {
                if (bound.stopped().getAsBoolean()) throw new IllegalStateException("任务已停止");
                var item = containers.require(containerId, bound.ownerUserId(), false);
                if (!bound.applicationId().equals(item.getApplicationId())) throw new IllegalArgumentException("只能操作当前项目的容器");
                return containers.action(containerId, "start", bound.ownerUserId(), false);
            };
        }
        @Tool("停止当前项目未上线容器")
        public Object stopProjectContainer(String containerId) { owned(containerId); return (DeferredToolResult) bound -> containers.action(containerId, "stop", bound.ownerUserId(), false); }
    }
}
