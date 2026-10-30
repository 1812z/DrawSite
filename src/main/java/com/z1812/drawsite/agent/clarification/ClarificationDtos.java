package com.z1812.drawsite.agent.clarification;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.List;

public final class ClarificationDtos {
    private ClarificationDtos() {}
    public record Question(@NotBlank @Size(max = 1000) String question,
                           @NotNull @Size(max = 5) List<@NotBlank @Size(max = 200) String> options) {}
    public record Answer(@Min(0) @Max(2) int questionIndex,
                         @NotNull @Size(max = 5) List<@NotBlank @Size(max = 200) String> selectedOptions,
                         @Size(max = 4000) String customText) {}
    public record Reply(@NotNull @Size(min = 1, max = 3) List<@Valid Answer> answers) {}
    public record View(String id, String runId, String conversationId, String userMessageId, List<Question> questions,
                       List<Answer> answers, String status, OffsetDateTime createdAt, OffsetDateTime answeredAt) {}
}
