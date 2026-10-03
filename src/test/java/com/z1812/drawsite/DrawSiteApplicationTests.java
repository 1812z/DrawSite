package com.z1812.drawsite;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class DrawSiteApplicationTests {

    private static final java.nio.file.Path WORKSPACE = createTestWorkspace();

    private static java.nio.file.Path createTestWorkspace() {
        try { return java.nio.file.Files.createTempDirectory("drawsite-test-"); }
        catch (java.io.IOException exception) { throw new java.io.UncheckedIOException(exception); }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("drawsite.workspace-root", WORKSPACE::toString);
    }

    @LocalServerPort
    private int port;

    @Test
    void healthEndpointReturnsUnifiedResponse() throws Exception {
        var response = get("/api/system/health");
        var body = JsonMapper.builder().build().readTree(response.body());
        assertEquals(200, response.statusCode());
        assertEquals("OK", body.path("code").asText());
        assertEquals("UP", body.path("data").path("status").asText());
    }

    @Test
    void missingEndpointReturnsUnifiedNotFound() throws Exception {
        var response = get("/missing");
        var body = JsonMapper.builder().build().readTree(response.body());
        assertEquals(404, response.statusCode());
        assertEquals("HTTP_404", body.path("code").asText());
    }

    @Test
    void authenticationAndCrossUserIsolation() throws Exception {
        assertEquals(401, send("GET", "/api/applications", null, null).statusCode());
        String suffix = java.util.UUID.randomUUID().toString().substring(0, 8);
        String userA = "a_" + suffix;
        String userB = "b_" + suffix;
        String registration = "{\"username\":\"%s\",\"password\":\"password123\",\"displayName\":\"测试用户\"}";
        var registered = send("POST", "/api/auth/register", registration.formatted(userA), null);
        assertEquals(201, registered.statusCode());
        assertEquals("USER", json(registered).path("data").path("role").asText());
        assertEquals(409, send("POST", "/api/auth/register", registration.formatted(userA), null).statusCode());
        assertEquals(201, send("POST", "/api/auth/register", registration.formatted(userB), null).statusCode());
        assertEquals(401, send("POST", "/api/auth/login", "{\"username\":\"" + userA + "\",\"password\":\"incorrect\"}", null).statusCode());
        String tokenA = login(userA);
        String tokenB = login(userB);
        var application = send("POST", "/api/applications", "{\"name\":\"商品系统\",\"description\":\"测试\"}", tokenA);
        assertEquals(201, application.statusCode(), application.body());
        String applicationId = json(application).path("data").path("id").asText();
        assertEquals("READY", json(application).path("data").path("status").asText());
        assertTrue(java.nio.file.Files.isDirectory(WORKSPACE.resolve(applicationId).resolve(".git")));
        String base = "/api/applications/" + applicationId;
        assertEquals(404, send("GET", base, null, tokenB).statusCode());
        assertEquals(404, send("PUT", base, "{\"name\":\"越权修改\"}", tokenB).statusCode());
        assertEquals(404, send("GET", base + "/documents?path=AGENTS.md", null, tokenB).statusCode());
        assertEquals(0, json(send("GET", "/api/applications", null, tokenB)).path("data").path("total").asInt());
        assertEquals(1, json(send("GET", base + "/revisions", null, tokenA)).path("data").path("total").asInt());
        assertEquals(200, send("GET", base + "/documents?path=progress.json", null, tokenA).statusCode());
        var conversation = send("POST", base + "/conversations", "{\"title\":\"需求讨论\"}", tokenA);
        assertEquals(201, conversation.statusCode());
        String conversationId = json(conversation).path("data").path("id").asText();
        String messages = base + "/conversations/" + conversationId + "/messages";
        assertEquals(201, send("POST", messages, "{\"content\":\"需要商品管理\"}", tokenA).statusCode());
        assertEquals(404, send("GET", messages, null, tokenB).statusCode());
        assertEquals(404, send("POST", messages, "{\"content\":\"越权消息\"}", tokenB).statusCode());
        assertEquals(1, json(send("GET", messages, null, tokenA)).path("data").path("total").asInt());
        assertEquals(200, send("POST", "/api/auth/logout", null, tokenA).statusCode());
        assertEquals(401, send("GET", "/api/auth/me", null, tokenA).statusCode());
    }

    private String login(String username) throws Exception {
        var response = send("POST", "/api/auth/login", "{\"username\":\"" + username + "\",\"password\":\"password123\"}", null);
        assertEquals(200, response.statusCode(), response.body());
        return json(response).path("data").path("token").asText();
    }

    private tools.jackson.databind.JsonNode json(HttpResponse<String> response) throws Exception {
        return JsonMapper.builder().build().readTree(response.body());
    }

    private HttpResponse<String> send(String method, String path, String body, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        try (var client = HttpClient.newHttpClient()) {
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private HttpResponse<String> get(String path) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
        }
    }

}
