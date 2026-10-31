package com.z1812.drawsite.docker;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerMapping;
import java.nio.file.*;

@RestController
public class PublishedSiteController {
    private final DeploymentService deployments;
    private final PreviewAccess previews;
    public PublishedSiteController(DeploymentService deployments, PreviewAccess previews) { this.deployments = deployments; this.previews = previews; }

    @GetMapping("/sites/{applicationId}")
    public ResponseEntity<Void> redirect(@PathVariable String applicationId) {
        if (!applicationId.matches("[a-fA-F0-9-]{36}")) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        deployments.siteRoot(applicationId);
        return ResponseEntity.status(HttpStatus.PERMANENT_REDIRECT).header("Location", "/sites/" + applicationId + "/").build();
    }

    @GetMapping("/sites/{applicationId}/**")
    public ResponseEntity<FileSystemResource> file(@PathVariable String applicationId, HttpServletRequest request) {
        String path = (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
        String relative = path.substring(("/sites/" + applicationId + "/").length());
        Path root;
        if (relative.startsWith("__preview/")) {
            root = previews.site(applicationId, request);
            if (root == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            relative = relative.substring("__preview/".length());
            for (String part : relative.split("/")) if (part.startsWith(".") || java.util.Set.of("node_modules", "AGENTS.md", "progress.json", "docs", "api").contains(part)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            String lower = relative.toLowerCase(java.util.Locale.ROOT);
            if (lower.endsWith(".key") || lower.endsWith(".pem") || lower.endsWith(".sql") || lower.endsWith(".java") || lower.endsWith(".py")) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        } else root = deployments.siteRoot(applicationId);
        root = root.toAbsolutePath().normalize();
        Path file = root.resolve(relative).normalize();
        if (!file.startsWith(root)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        for (Path p = file; p != null; p = p.getParent()) if (Files.isSymbolicLink(p)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (Files.isDirectory(file)) file = file.resolve("index.html");
        // 无扩展名的路径回退到首页，兼容SPA history路由
        if (!Files.isRegularFile(file) && !file.getFileName().toString().contains(".")) file = root.resolve("index.html");
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        MediaType type = name.endsWith(".js") || name.endsWith(".mjs") ? MediaType.parseMediaType("text/javascript")
                : name.endsWith(".wasm") ? MediaType.parseMediaType("application/wasm")
                : MediaTypeFactory.getMediaType(name).orElse(MediaType.APPLICATION_OCTET_STREAM);
        return ResponseEntity.ok().contentType(type).cacheControl(CacheControl.noCache())
                .header("X-Content-Type-Options", "nosniff").body(new FileSystemResource(file));
    }
}
