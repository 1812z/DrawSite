package com.z1812.drawsite.application;

import com.z1812.drawsite.common.api.ApiResponse;
import com.z1812.drawsite.common.exception.BusinessException;
import com.z1812.drawsite.workspace.ProjectGitService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/applications/{app}/git")
public class GitController {
    private final ApplicationService applications;
    private final ApplicationMapper mapper;
    private final ProjectGitService git;
    public GitController(ApplicationService applications, ApplicationMapper mapper, ProjectGitService git) { this.applications = applications; this.mapper = mapper; this.git = git; }
    public record CommitRequest(@NotBlank @Size(max = 200) String message, @NotBlank String expectedHead,
                                @Size(min = 1, max = 1000) java.util.List<@NotBlank String> files) {}
    public record RollbackRequest(@NotBlank String target, @NotBlank String expectedHead) {}
    @GetMapping("/status") @Transactional
    public ApiResponse<ProjectGitService.State> status(@PathVariable String app) throws Exception { applications.lockOwned(app); return ApiResponse.success(git.status(app)); }
    @GetMapping("/tree") @Transactional
    public ApiResponse<ProjectGitService.History> tree(@PathVariable String app, @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit) throws Exception { applications.lockOwned(app); return ApiResponse.success(git.history(app, limit)); }
    private void requireIdle(String app) {
        var application = applications.lockOwned(app);
        if (!"READY".equals(application.getStatus()) || mapper.activeRuns(app) > 0) throw new BusinessException("APPLICATION_BUSY", "项目运行中，请停止任务后操作Git", HttpStatus.CONFLICT);
    }
    @PostMapping("/commits") @Transactional(rollbackFor = Exception.class)
    public ApiResponse<ProjectGitService.State> commit(@PathVariable String app, @Valid @RequestBody CommitRequest request) throws Exception { requireIdle(app); return ApiResponse.success(git.commit(app, request.message(), request.expectedHead(), request.files())); }
    @PostMapping("/rollback") @Transactional(rollbackFor = Exception.class)
    public ApiResponse<ProjectGitService.State> rollback(@PathVariable String app, @Valid @RequestBody RollbackRequest request) throws Exception { requireIdle(app); return ApiResponse.success(git.rollback(app, request.target(), request.expectedHead())); }
}
