package com.z1812.drawsite.system;

import com.z1812.drawsite.common.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/system")
public class SystemController {

    @GetMapping("/health")
    public ApiResponse<HealthStatus> health() {
        return ApiResponse.success(new HealthStatus("DrawSite", "UP", Instant.now()));
    }

    public record HealthStatus(String application, String status, Instant timestamp) {
    }
}
