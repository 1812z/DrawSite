package com.z1812.drawsite.application;

import com.z1812.drawsite.common.api.ApiResponse;
import com.z1812.drawsite.common.exception.BusinessException;
import com.z1812.drawsite.workspace.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
public class ProgressController {
    private final ApplicationService applications;
    private final WorkspaceService workspace;
    public ProgressController(ApplicationService applications, WorkspaceService workspace) {
        this.applications = applications; this.workspace = workspace;
    }
    @GetMapping("/api/applications/{id}/progress")
    public ApiResponse<ProgressDocument.Summary> get(@PathVariable String id) throws java.io.IOException {
        applications.requireOwned(id);
        try { return ApiResponse.success(ProgressDocument.parse(workspace.readDocument(id, "progress.json"))); }
        catch (IllegalArgumentException error) { throw new BusinessException("PROGRESS_INVALID", error.getMessage(), HttpStatus.CONFLICT); }
    }
}
