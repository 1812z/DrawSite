package com.z1812.drawsite.agent;

import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;

public final class AgentDtos {
    private AgentDtos() {}
    public enum ReasoningEffort { DEFAULT, LOW, MEDIUM, HIGH }
    public record ChatRequest(@NotBlank @Size(max = 20000) String content,
                              @Size(max = 64) String modelId,
                              ReasoningEffort reasoningEffort, Boolean memoryEnabled) {}
    public record RunView(String id, String conversationId, String status, String modelId,
                          String reasoningEffort, boolean memoryEnabled, String userMessageId,
                          String assistantMessageId, String content, String errorCode,
                          OffsetDateTime createdAt, OffsetDateTime finishedAt) {}
    public record MemoryRequest(@NotBlank @Pattern(regexp = "USER|APPLICATION") String scope,
                                @NotBlank @Size(max = 4000) String content) {}
}
