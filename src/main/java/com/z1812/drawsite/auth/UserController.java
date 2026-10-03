package com.z1812.drawsite.auth;

import com.z1812.drawsite.common.api.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import static com.z1812.drawsite.auth.UserDtos.*;

@RestController
public class UserController {
    private final UserService users;
    public UserController(UserService users) { this.users = users; }

    @PutMapping("/api/users/me")
    public ApiResponse<AuthDtos.UserView> updateProfile(@Valid @RequestBody ProfileRequest request) {
        return ApiResponse.success(users.updateProfile(request));
    }

    @PutMapping("/api/users/me/password")
    public ApiResponse<Void> changePassword(@Valid @RequestBody PasswordRequest request) {
        users.changePassword(request);
        return ApiResponse.success(null);
    }

    @GetMapping("/api/admin/users")
    public ApiResponse<PageResponse<AdminUserView>> list(@RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(users.list(page, size));
    }

    @GetMapping("/api/admin/users/{id}")
    public ApiResponse<AdminUserView> detail(@PathVariable String id) {
        return ApiResponse.success(users.detail(id));
    }

    @PutMapping("/api/admin/users/{id}")
    public ApiResponse<AdminUserView> update(@PathVariable String id, @Valid @RequestBody AdminUpdateRequest request) {
        return ApiResponse.success(users.update(id, request));
    }

    @PutMapping("/api/admin/users/{id}/password")
    public ApiResponse<Void> resetPassword(@PathVariable String id, @Valid @RequestBody ResetPasswordRequest request) {
        users.resetPassword(id, request);
        return ApiResponse.success(null);
    }
}
