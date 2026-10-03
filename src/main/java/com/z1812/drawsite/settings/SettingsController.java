package com.z1812.drawsite.settings;

import com.z1812.drawsite.common.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import static com.z1812.drawsite.settings.SettingsDtos.*;

@RestController
public class SettingsController {
    private final SettingsService settings;
    public SettingsController(SettingsService settings) { this.settings = settings; }

    @GetMapping("/api/platform/public-settings")
    public ApiResponse<PublicSettings> publicSettings() { return ApiResponse.success(settings.publicSettings()); }

    @GetMapping("/api/admin/settings")
    public ApiResponse<AdminSettings> adminSettings() { return ApiResponse.success(settings.adminSettings()); }

    @PutMapping("/api/admin/settings")
    public ApiResponse<AdminSettings> update(@Valid @RequestBody UpdateRequest request) {
        return ApiResponse.success(settings.update(request));
    }
}
