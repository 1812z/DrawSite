package com.z1812.drawsite.tool.builtin;

import com.z1812.drawsite.tool.framework.*;
import com.z1812.drawsite.workspace.NetworkService;
import dev.langchain4j.agent.tool.*;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class NetworkToolModule implements ProjectToolModule {
    private final NetworkService network;
    public NetworkToolModule(NetworkService network) { this.network = network; }
    @Override public String id() { return "network"; }
    @Override public String name() { return "网络访问"; }
    @Override public String description() { return "访问URL、发送HTTP请求并下载资源到项目"; }
    @Override public Object bind(ToolContext context) { return new NetworkTools(network); }
    public record NetworkTools(NetworkService network) {
        @Tool("访问HTTP(S) URL，支持GET/POST/PUT/PATCH/DELETE/HEAD/OPTIONS，默认GET。请求头为字符串键值，body为文本。最多30秒和2MB，不自动跟随重定向，返回状态码、响应头及文本")
        public Object httpRequest(String url, @P(value = "HTTP方法，默认GET", required = false) String method,
                                  @P(value = "请求头对象", required = false) Map<String, String> headers,
                                  @P(value = "请求体文本", required = false) String body) {
            var args = new HashMap<String, Object>();
            args.put("url", url); args.put("method", method); args.put("headers", headers); args.put("body", body);
            return (DeferredToolResult) context -> network.execute("httpRequest", args, context);
        }
        @Tool("GET下载资源到项目相对路径，最多2MB、30秒，新建expectedHash=NEW，覆盖使用现有文件sha256，不自动跟随重定向")
        public Object downloadResource(String url, String path, String expectedHash) {
            return (DeferredToolResult) context -> network.execute("downloadResource", Map.of("url", url, "path", path, "expectedHash", expectedHash), context);
        }
    }
}
