package com.z1812.drawsite.agent;

import com.z1812.drawsite.agent.memory.MemoryMapper;
import com.z1812.drawsite.tool.builtin.GlobalMemoryToolModule;
import com.z1812.drawsite.tool.framework.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GlobalMemoryTests {
    @Test
    void disabledMemoryIsNotExposedAndDirectCallsAreRejected() {
        var mapper = mock(MemoryMapper.class);
        var module = new GlobalMemoryToolModule(mapper);
        var context = new ToolContext("app", "owner", "run", () -> false, waiting -> {}, false);
        var registry = new ProjectToolRegistry(List.of(module), null);
        assertTrue(registry.bind(context, java.util.Set.of("globalMemory")).specifications().isEmpty());
        var tools = (GlobalMemoryToolModule.MemoryTools) module.bind(context);
        assertThrows(RuntimeException.class, tools::readGlobalMemory);
        assertThrows(RuntimeException.class, () -> tools.writeGlobalMemory("preference", "NEW"));
        verifyNoInteractions(mapper);
    }

    @Test
    void updatesRequireFreshHashAndConditionalWrite() {
        var mapper = mock(MemoryMapper.class);
        var tools = (GlobalMemoryToolModule.MemoryTools) new GlobalMemoryToolModule(mapper)
                .bind(new ToolContext("app", "owner", "run", () -> false));
        when(mapper.get("owner")).thenReturn(new MemoryMapper.Memory("old", null));
        var snapshot = tools.readGlobalMemory();
        assertThrows(RuntimeException.class, () -> tools.writeGlobalMemory("new", "NEW"));
        when(mapper.replaceIfUnchanged("owner", "new", "old")).thenReturn(0);
        assertThrows(RuntimeException.class, () -> tools.writeGlobalMemory("new", snapshot.sha256()));
        when(mapper.replaceIfUnchanged("owner", "new", "old")).thenReturn(1);
        assertEquals("new", tools.writeGlobalMemory("new", snapshot.sha256()).content());
        assertThrows(IllegalArgumentException.class, () -> tools.writeGlobalMemory("x".repeat(4001), snapshot.sha256()));
    }
}
