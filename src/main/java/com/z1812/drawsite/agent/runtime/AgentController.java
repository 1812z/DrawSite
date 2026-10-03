package com.z1812.drawsite.agent.runtime;

import com.z1812.drawsite.common.api.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/applications/{applicationId}")
public class AgentController {
    private final AgentService agent;
    public AgentController(AgentService agent) { this.agent = agent; }

    @PostMapping("/conversations/{conversationId}/runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<AgentDtos.RunView> start(@PathVariable String applicationId, @PathVariable String conversationId,
                                               @Valid @RequestBody AgentDtos.ChatRequest request) {
        return ApiResponse.success(agent.start(applicationId, conversationId, request));
    }
    @GetMapping("/conversations/{conversationId}/runs/{runId}")
    public ApiResponse<AgentDtos.RunView> get(@PathVariable String applicationId, @PathVariable String conversationId, @PathVariable String runId) {
        return ApiResponse.success(agent.get(applicationId, conversationId, runId));
    }
    @PostMapping("/conversations/{conversationId}/runs/{runId}/cancel")
    public ApiResponse<AgentDtos.RunView> cancel(@PathVariable String applicationId, @PathVariable String conversationId, @PathVariable String runId) {
        return ApiResponse.success(agent.cancel(applicationId, conversationId, runId));
    }
    @GetMapping(value = "/conversations/{conversationId}/runs/{runId}/events", produces = "text/event-stream")
    public SseEmitter events(@PathVariable String applicationId, @PathVariable String conversationId, @PathVariable String runId) {
        return agent.events(applicationId, conversationId, runId);
    }
}
