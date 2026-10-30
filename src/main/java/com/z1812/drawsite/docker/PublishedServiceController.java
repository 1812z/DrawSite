package com.z1812.drawsite.docker;

import jakarta.servlet.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.beans.factory.annotation.Value;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

@RestController
public class PublishedServiceController {
    private static final Set<String> HOP = Set.of("connection", "keep-alive", "proxy-authenticate", "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade", "host", "content-length", "expect");
    private final ContainerMapper containers;
    private final DeploymentMapper deployments;
    private final String dockerHost;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    public PublishedServiceController(ContainerMapper containers, DeploymentMapper deployments,
                                      @Value("${drawsite.container-host:host.docker.internal}") String dockerHost) {
        this.containers = containers; this.deployments = deployments; this.dockerHost = dockerHost;
    }
    @GetMapping("/services/{id}/{slot:[12]}")
    public void redirect(@PathVariable String id, @PathVariable int slot, HttpServletResponse response) {
        response.setStatus(308);
        response.setHeader("Location", "/services/" + id + "/" + slot + "/");
    }
    @RequestMapping("/services/{id}/{slot:[12]}/**")
    public void proxy(@PathVariable String id, @PathVariable int slot, HttpServletRequest request, HttpServletResponse response) throws Exception {
        var container = containers.selectOneById(id);
        var deployment = deployments.forContainer(id);
        if (container == null || deployment == null || !"ONLINE".equals(deployment.getStatus()) || !"RUNNING".equals(container.getStatus())) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        Integer port = slot == 1 ? container.getHostPort() : container.getSecondaryHostPort();
        if (port == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (!Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS").contains(request.getMethod())) throw new ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED);
        String prefix = "/services/" + id + "/" + slot + "/";
        String suffix = request.getRequestURI().substring(request.getContextPath().length() + prefix.length());
        String query = request.getQueryString();
        var builder = HttpRequest.newBuilder(URI.create("http://" + dockerHost + ":" + port + "/" + suffix + (query == null ? "" : "?" + query)))
                .timeout(Duration.ofSeconds(120));
        var connectionHeaders = new HashSet<String>(HOP);
        String connection = request.getHeader("Connection");
        if (connection != null) for (String value : connection.split(",")) connectionHeaders.add(value.strip().toLowerCase(Locale.ROOT));
        var names = request.getHeaderNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            if (connectionHeaders.contains(name.toLowerCase(Locale.ROOT)) || name.toLowerCase(Locale.ROOT).startsWith("x-forwarded-")) continue;
            var values = request.getHeaders(name);
            while (values.hasMoreElements()) builder.header(name, values.nextElement());
        }
        builder.header("X-Forwarded-Prefix", prefix.substring(0, prefix.length() - 1));
        builder.header("X-Forwarded-Host", request.getHeader("Host"));
        builder.header("X-Forwarded-Proto", "https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto")) || request.isSecure() ? "https" : "http");
        builder.method(request.getMethod(), request.getContentLengthLong() == 0 || (request.getContentLengthLong() < 0 && request.getHeader("Transfer-Encoding") == null)
                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofInputStream(() -> {
            try { return request.getInputStream(); } catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
        }));
        HttpResponse<java.io.InputStream> upstream;
        try { upstream = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream()); }
        catch (java.io.IOException error) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "服务不可达，请检查监听端口及CONTAINER_HOST", error); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "服务请求已中断", error); }
        response.setStatus(upstream.statusCode());
        var excluded = new HashSet<String>(HOP);
        upstream.headers().allValues("connection").forEach(value -> {
            for (String name : value.split(",")) excluded.add(name.strip().toLowerCase(Locale.ROOT));
        });
        upstream.headers().map().forEach((name, values) -> {
            if (!excluded.contains(name.toLowerCase(Locale.ROOT))) for (String value : values) {
                if (name.equalsIgnoreCase("location") && value.startsWith("/") && !value.startsWith("//")) value = prefix.substring(0, prefix.length() - 1) + value;
                if (name.equalsIgnoreCase("set-cookie")) value = value.replaceAll("(?i)(;\\s*Path=)/", "$1" + prefix);
                response.addHeader(name, value);
            }
        });
        try (var body = upstream.body()) { if (!"HEAD".equals(request.getMethod())) body.transferTo(response.getOutputStream()); }
    }
}
