package com.z1812.drawsite.docker;

import org.springframework.stereotype.Component;
import jakarta.servlet.http.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class PreviewAccess {
    private record Grant(String containerId, long expires) {}
    private final Map<String, java.nio.file.Path> roots = new ConcurrentHashMap<>();
    public void grantSite(String app, java.nio.file.Path root, HttpServletResponse response) {
        grant(app, response);
        roots.put(app, root);
    }
    public java.nio.file.Path site(String app, HttpServletRequest request) {
        return allowed(app, request) ? roots.get(app) : null;
    }
    private final Map<String, Grant> grants = new ConcurrentHashMap<>();
    public void grant(String containerId, HttpServletResponse response) {
        grants.entrySet().removeIf(entry -> entry.getValue().expires() < System.currentTimeMillis());
        String token = UUID.randomUUID() + "-" + UUID.randomUUID();
        grants.put(token, new Grant(containerId, System.currentTimeMillis() + 3600000));
        response.addHeader("Set-Cookie", "drawsite_preview=" + token + "; Path=/services/" + containerId + "/; HttpOnly; SameSite=Strict; Max-Age=3600");
        response.addHeader("Set-Cookie", "drawsite_preview=" + token + "; Path=/sites/" + containerId + "/__preview/; HttpOnly; SameSite=Strict; Max-Age=3600");
    }
    public boolean allowed(String containerId, HttpServletRequest request) {
        if (request.getCookies() == null) return false;
        for (var cookie : request.getCookies()) if ("drawsite_preview".equals(cookie.getName())) {
            var grant = grants.get(cookie.getValue());
            if (grant != null && grant.containerId().equals(containerId) && grant.expires() > System.currentTimeMillis()) return true;
        }
        return false;
    }
}
