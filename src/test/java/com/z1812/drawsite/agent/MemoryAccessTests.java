package com.z1812.drawsite.agent;

import com.z1812.drawsite.workspace.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class MemoryAccessTests {
    @TempDir Path root;

    @Test
    void disabledMemoryIsHiddenAndCannotBeModifiedOrMoved() throws Exception {
        String id = UUID.randomUUID().toString();
        Path project = Files.createDirectory(root.resolve(id));
        Files.writeString(project.resolve("AGENTS.md"), "private-memory");
        Files.writeString(project.resolve("visible.md"), "ordinary");
        var files = new ProjectFileService(root.toString());
        String hash = files.read(id, "AGENTS.md", 1, 10).sha256();
        try (var scope = FileAccessScope.memory(false)) {
            assertFalse(files.list(id, "", 5).entries().stream().anyMatch(entry -> entry.path().equals("AGENTS.md")));
            assertTrue(files.search(id, "", "private-memory").matches().isEmpty());
            assertThrows(RuntimeException.class, () -> files.read(id, "./AGENTS.md", 1, 10));
            assertThrows(RuntimeException.class, () -> files.write(id, "agents.md", "changed", "NEW"));
            assertThrows(RuntimeException.class, () -> files.delete(id, "AGENTS.md", hash));
            assertThrows(RuntimeException.class, () -> files.move(id, "AGENTS.md", "copy.md", hash));
            assertThrows(RuntimeException.class, () -> files.move(id, "visible.md", "AGENTS.md", "hash"));
            assertThrows(RuntimeException.class, () -> files.patch(id, "AGENTS.md", "diff", hash));
            assertThrows(RuntimeException.class, () -> files.replace(id, "AGENTS.md", "private", "public", hash));
        }
        try (var scope = FileAccessScope.memory(true)) {
            files.write(id, "AGENTS.md", "updated", hash);
            assertEquals("updated", files.read(id, "AGENTS.md", 1, 10).content());
        }
    }
}
