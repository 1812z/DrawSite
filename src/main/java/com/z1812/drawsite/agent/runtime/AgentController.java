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

    public record CompressRequest(@NotBlank String modelId, boolean memoryEnabled) {}
    @PostMapping(value = "/conversations/{conversationId}/context/compress/events", produces = "text/event-stream")
    public SseEmitter compressEvents(@PathVariable String applicationId, @PathVariable String conversationId,
                                     @Valid @RequestBody CompressRequest request) {
        return agent.compressEvents(applicationId, conversationId, request.modelId(), request.memoryEnabled());
    }
    @GetMapping("/conversations/{conversationId}/context")
    public ApiResponse<ConversationContextService.View> context(@PathVariable String applicationId, @PathVariable String conversationId,
                                                               @RequestParam(defaultValue = "true") boolean memoryEnabled) {
        return ApiResponse.success(agent.context(applicationId, conversationId, memoryEnabled));
    }
    @PostMapping("/conversations/{conversationId}/context/compress")
    public ApiResponse<ConversationContextService.View> compress(@PathVariable String applicationId, @PathVariable String conversationId,
                                                                @Valid @RequestBody CompressRequest request) {
        return ApiResponse.success(agent.compress(applicationId, conversationId, request.modelId(), request.memoryEnabled()));
    }

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
