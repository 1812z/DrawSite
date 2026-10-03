package com.z1812.drawsite.agent.runtime;

import java.time.OffsetDateTime;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;

public record RunStep(String id, int round, String type, String title, String status,
                      String content, String toolName, OffsetDateTime startedAt, OffsetDateTime finishedAt) {
    private static final JsonMapper JSON = JsonMapper.builder()
            .disable(tools.jackson.databind.cfg.DateTimeFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE).build();

    public static String encode(List<RunStep> steps) { return JSON.writeValueAsString(steps); }
    public static List<RunStep> decode(String value) {
        if (value == null || value.isBlank()) return List.of();
        return JSON.readValue(value, JSON.getTypeFactory().constructCollectionType(List.class, RunStep.class));
    }
}
