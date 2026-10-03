package com.z1812.drawsite.tool.framework;

public interface ProjectToolModule {
    default boolean available(ToolContext context) { return true; }
    String id();
    String name();
    String description();
    Object bind(ToolContext context);
}
