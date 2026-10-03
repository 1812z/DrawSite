package com.z1812.drawsite.tool.framework;

import java.util.function.BooleanSupplier;

public record ToolContext(String applicationId, String ownerUserId, String runId, BooleanSupplier stopped,
                          java.util.function.Consumer<Boolean> waitingForUser, boolean memoryEnabled) {
    public ToolContext(String applicationId, String ownerUserId, String runId, BooleanSupplier stopped) {
        this(applicationId, ownerUserId, runId, stopped, waiting -> {}, true);
    }
    public ToolContext(String applicationId, String ownerUserId, String runId, BooleanSupplier stopped,
                       java.util.function.Consumer<Boolean> waitingForUser) {
        this(applicationId, ownerUserId, runId, stopped, waitingForUser, true);
    }
}
