package com.z1812.drawsite.tool.framework;

public interface DeferredToolResult {
    Object await(ToolContext context) throws Exception;
}
