package com.z1812.drawsite.agent.runtime;

import java.util.*;
import com.z1812.drawsite.agent.runtime.AgentDtos.RunView;

// offset 表示保留旧文本的前多少个 UTF-16 字符，其余替换为新增内容。
public record RunDelta(long sequence, RunView state, TextPatch content, List<StepPatch> steps, List<String> removed) {
    public record TextPatch(int offset, String text) {}
    public record StepPatch(RunStep state, TextPatch content) {}

    private static TextPatch patch(String before, String after) {
        if (Objects.equals(before, after)) return null;
        int offset = after.startsWith(before) ? before.length() : 0;
        return new TextPatch(offset, after.substring(offset));
    }

    public static RunDelta between(long sequence, RunView before, RunView after) {
        var oldSteps = new HashMap<String, RunStep>();
        before.steps().forEach(step -> oldSteps.put(step.id(), step));
        var changes = new ArrayList<StepPatch>();
        for (var step : after.steps()) {
            var old = oldSteps.remove(step.id());
            if (step.equals(old)) continue;
            var state = new RunStep(step.id(), step.round(), step.type(), step.title(), step.status(), "",
                    step.toolName(), step.startedAt(), step.finishedAt());
            changes.add(new StepPatch(state, patch(old == null ? "" : old.content(), step.content())));
        }
        var state = new RunView(after.id(), after.conversationId(), after.status(), after.modelId(), after.reasoningEffort(),
                after.memoryEnabled(), after.userMessageId(), after.assistantMessageId(), "", after.errorCode(),
                after.createdAt(), after.finishedAt(), after.phase(), List.of(), after.pendingQuestion());
        return new RunDelta(sequence, state, patch(before.content(), after.content()), changes, new ArrayList<>(oldSteps.keySet()));
    }
}
