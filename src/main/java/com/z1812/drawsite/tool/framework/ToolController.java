package com.z1812.drawsite.tool.framework;

import com.z1812.drawsite.common.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

@RestController
public class ToolController {
    private final ProjectToolRegistry tools;
    private final com.z1812.drawsite.application.ApplicationService applications;
    public ToolController(ProjectToolRegistry tools, com.z1812.drawsite.application.ApplicationService applications) {
        this.tools = tools; this.applications = applications;
    }

    @GetMapping("/api/agent/tools")
    public ApiResponse<List<ProjectToolRegistry.ToolView>> list() {
        return ApiResponse.success(tools.catalog());
    }
    @GetMapping("/api/applications/{applicationId}/tools")
    public ApiResponse<List<ProjectToolRegistry.ToolView>> project(
            @org.springframework.web.bind.annotation.PathVariable String applicationId) {
        applications.requireOwned(applicationId);
        return ApiResponse.success(tools.catalog(applicationId));
    }
}
