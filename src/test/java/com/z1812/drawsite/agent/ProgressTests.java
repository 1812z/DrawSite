package com.z1812.drawsite.agent;

import com.z1812.drawsite.workspace.ProgressDocument;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProgressTests {
    @Test void countsThreeStatesAndRejectsInvalidDocuments() {
        String valid = "{\"schemaVersion\":\"1.0\",\"features\":[{\"id\":\"a\",\"title\":\"A\",\"status\":\"DONE\"},{\"id\":\"b\",\"title\":\"B\",\"status\":\"IN_PROGRESS\"},{\"id\":\"c\",\"title\":\"C\",\"status\":\"TODO\"}]}";
        var summary = ProgressDocument.parse(valid);
        assertEquals("1/3", summary.completion());
        assertEquals(1, summary.inProgress());
        assertEquals(1, summary.pending());
        assertThrows(IllegalArgumentException.class, () -> ProgressDocument.parse(valid.replace("DONE", "SUCCEEDED")));
        assertThrows(IllegalArgumentException.class, () -> ProgressDocument.parse(valid.replace("\"id\":\"b\"", "\"id\":\"a\"")));
        assertThrows(IllegalArgumentException.class, () -> ProgressDocument.parse(valid.replace("\"title\":\"A\"", "\"title\":\"A\",\"extra\":1")));
        assertThrows(IllegalArgumentException.class, () -> ProgressDocument.parse(valid.replace("\"title\":\"A\"", "\"title\":\"A\",\"title\":\"X\"")));
        assertThrows(IllegalArgumentException.class, () -> ProgressDocument.parse(valid + " {}"));
        assertEquals("0/0", ProgressDocument.parse("{\"schemaVersion\":\"1.0\",\"features\":[]}").completion());
    }
}
