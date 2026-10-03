package com.z1812.drawsite.workspace;

public final class FileAccessScope implements AutoCloseable {
    private static final ThreadLocal<Boolean> MEMORY = new ThreadLocal<>();
    private final Boolean previous;
    private FileAccessScope(boolean enabled) {
        previous = MEMORY.get();
        MEMORY.set(enabled);
    }
    public static FileAccessScope memory(boolean enabled) { return new FileAccessScope(enabled); }
    public static boolean hidden(String name) { return Boolean.FALSE.equals(MEMORY.get()) && name.equalsIgnoreCase("AGENTS.md"); }
    @Override public void close() {
        if (previous == null) MEMORY.remove(); else MEMORY.set(previous);
    }
}
