package com.z1812.drawsite.tool.builtin;

import com.z1812.drawsite.docker.*;
import com.z1812.drawsite.tool.framework.*;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

@Component
public class StaticSiteToolModule implements ProjectToolModule {
    private final DeploymentService deployments;
    public StaticSiteToolModule(DeploymentService deployments) { this.deployments = deployments; }
    public String id() { return "staticSite"; }
    public String name() { return "静态发布"; }
    public String description() { return "管理纯静态站点配置"; }
    public Object bind(ToolContext context) { return new Tools(deployments, context); }
    public record Tools(DeploymentService deployments, ToolContext context) {
        @Tool("查询当前项目静态网站发布状态、directory及url")
        public Object getStaticSite() {
            return deployments.list(context.ownerUserId(), false).stream()
                    .filter(item -> context.applicationId().equals(item.getApplicationId()) && "STATIC".equals(item.getType())).toList();
        }
        @Tool("上线或更新网站，与页面的更新网站按钮相同。首次调用发布，已上线时再次调用会重新复制最新静态文件并替换旧快照，URL不变，无需先下线。directory为项目内目录如.或dist，可调整发布目录，必须有index.html。先调用getStaticSite读取当前directory；修改代码不会自动更新已上线站点，完成用户要求的网站更新后应再次调用本工具。Vue等框架需先重新构建并配置base为/sites/{当前项目ID}/。验证失败保留原站点；纯HTML无需Docker")
        public Object publishStaticSite(String directory) {
            if (directory == null || directory.length() > 300) throw new IllegalArgumentException("发布目录不合法");
            return (DeferredToolResult) bound -> {
                if (bound.stopped().getAsBoolean()) throw new IllegalStateException("任务已停止");
                return deployments.publish(new DockerDtos.Publish(bound.applicationId(), "STATIC", null, directory), bound.ownerUserId());
            };
        }
        @Tool("下线当前项目静态网站，不删除项目源码")
        public Object offlineStaticSite() {
            return (DeferredToolResult) bound -> {
                if (bound.stopped().getAsBoolean()) throw new IllegalStateException("任务已停止");
                for (var item : deployments.list(bound.ownerUserId(), false)) {
                    if (bound.applicationId().equals(item.getApplicationId()) && "STATIC".equals(item.getType())) deployments.offline(item.getId(), bound.ownerUserId(), false);
                }
                return "静态网站已下线";
            };
        }
    }
}
