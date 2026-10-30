package com.z1812.drawsite.workspace;

import java.util.Locale;
import java.util.Set;

public final class GitFilePolicy {
    private GitFilePolicy() {}
    public static boolean system(String path) {
        String value = path.toLowerCase(Locale.ROOT);
        return Set.of("agents.md", "progress.json", "docs/requirements.md", "docs/design/overview.md", "docs/design/detail.md", "api/openapi.json").contains(value)
                || value.startsWith("api/tests/");
    }
    public static final String IGNORE = "\n/AGENTS.md\n/progress.json\n/docs/requirements.md\n/docs/design/overview.md\n/docs/design/detail.md\n/api/openapi.json\n/api/tests/\n";
}
