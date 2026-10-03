package com.z1812.drawsite.agent.runtime;

import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;

public final class AgentDtos {
    private AgentDtos() {}
    public enum ReasoningEffort { DEFAULT, LOW, MEDIUM, HIGH }
    public record ChatRequest(@NotBlank @Size(max = 20000) String content,
                              @Size(max = 64) String modelId,
                              ReasoningEffort reasoningEffort, Boolean memoryEnabled, Boolean toolsEnabled,
                              @Size(max = 100) java.util.List<@NotBlank @Size(max = 100) String> toolNames) {}
    public record RunView(String id, String conversationId, String status, String modelId,
                          String reasoningEffort, boolean memoryEnabled, String userMessageId,
                          String assistantMessageId, String content, String errorCode,
                          OffsetDateTime createdAt, OffsetDateTime finishedAt,
                          String phase, java.util.List<RunStep> steps,
                          com.z1812.drawsite.agent.clarification.ClarificationDtos.View pendingQuestion) {}
}
