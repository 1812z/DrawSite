package com.z1812.drawsite.agent;

import com.z1812.drawsite.tool.builtin.NetworkToolModule;
import com.z1812.drawsite.tool.framework.*;
import com.z1812.drawsite.workspace.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NetworkToolsTests {
    @TempDir Path root;
    @Test
    void builtinNetworkPostsAndDownloadsThroughGuard() throws Exception {
        var guard = mock(ToolGuard.class);
        when(guard.execute(any(), any())).thenAnswer(call -> ((ToolGuard.Action) call.getArgument(1)).execute());
        var files = new ProjectFileService(root.toString());
        String app = UUID.randomUUID().toString();
        Files.createDirectory(root.resolve(app));
        var service = new NetworkService(files, guard);
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] bytes = (exchange.getRequestMethod() + ":" + new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        try {
            var registry = new ProjectToolRegistry(List.of(new NetworkToolModule(service)), guard);
            var context = new ToolContext(app, "owner", "run", () -> false);
            var session = registry.bind(context, Set.of("network"));
            assertEquals(2, session.specifications().size());
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            String post = session.execute(dev.langchain4j.agent.tool.ToolExecutionRequest.builder().name("httpRequest")
                    .arguments("{\"url\":\"" + url + "\",\"method\":\"POST\",\"body\":\"hello\"}").build());
            assertTrue(post.contains("POST:hello"));
            String get = session.execute(dev.langchain4j.agent.tool.ToolExecutionRequest.builder().name("httpRequest").arguments("{\"url\":\"" + url + "\"}").build());
            assertTrue(get.contains("GET:"));
            String download = session.execute(dev.langchain4j.agent.tool.ToolExecutionRequest.builder().name("downloadResource")
                    .arguments("{\"url\":\"" + url + "\",\"path\":\"asset.txt\",\"expectedHash\":\"NEW\"}").build());
            assertTrue(download.contains("\"code\":\"OK\""));
            assertEquals("GET:", Files.readString(root.resolve(app).resolve("asset.txt")));
            assertTrue(registry.bind(context, Set.of()).specifications().isEmpty());
        } finally { server.stop(0); service.close(); }
    }
}
