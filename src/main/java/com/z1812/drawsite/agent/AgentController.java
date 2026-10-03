package com.z1812.drawsite.agent;

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
    private final MemoryService memory;
    public AgentController(AgentService agent, MemoryService memory) { this.agent = agent; this.memory = memory; }

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
    @GetMapping("/memories")
    public ApiResponse<PageResponse<MemoryEntity>> memories(@PathVariable String applicationId,
            @RequestParam(defaultValue = "1") @Min(1) int page, @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(memory.list(applicationId, page, size));
    }
    @PostMapping("/memories")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<MemoryEntity> createMemory(@PathVariable String applicationId, @Valid @RequestBody AgentDtos.MemoryRequest request) {
        return ApiResponse.success(memory.create(applicationId, request));
    }
    @PutMapping("/memories/{id}")
    public ApiResponse<MemoryEntity> updateMemory(@PathVariable String applicationId, @PathVariable String id,
                                                 @Valid @RequestBody AgentDtos.MemoryRequest request) {
        return ApiResponse.success(memory.update(applicationId, id, request));
    }
    @DeleteMapping("/memories/{id}")
    public ApiResponse<Void> deleteMemory(@PathVariable String applicationId, @PathVariable String id) {
        memory.delete(applicationId, id); return ApiResponse.success(null);
    }
}
