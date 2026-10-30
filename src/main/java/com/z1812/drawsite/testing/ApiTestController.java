package com.z1812.drawsite.testing;

import com.z1812.drawsite.application.ApplicationService;
import com.z1812.drawsite.common.api.ApiResponse;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/applications/{applicationId}/tests")
public class ApiTestController {
    private final ApplicationService applications;
    private final ApiTestService tests;
    public ApiTestController(ApplicationService applications, ApiTestService tests) { this.applications = applications; this.tests = tests; }
    @GetMapping public ApiResponse<ApiTestService.State> state(@PathVariable String applicationId) throws Exception {
        applications.requireOwned(applicationId); return ApiResponse.success(tests.state(applicationId));
    }
    @PostMapping("/runs") public ApiResponse<ApiTestRun> start(@PathVariable String applicationId) throws Exception {
        applications.requireOwned(applicationId); return ApiResponse.success(tests.start(applicationId, StpUtil.getLoginIdAsString()));
    }
    @GetMapping("/runs/{id}") public ApiResponse<ApiTestRun> report(@PathVariable String applicationId, @PathVariable String id) {
        applications.requireOwned(applicationId); return ApiResponse.success(tests.report(applicationId, id));
    }
}
