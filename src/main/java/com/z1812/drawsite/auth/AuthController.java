package com.z1812.drawsite.auth;

import cn.dev33.satoken.stp.StpUtil;
import com.z1812.drawsite.common.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import static com.z1812.drawsite.auth.AuthDtos.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;
    public AuthController(AuthService auth) { this.auth = auth; }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<UserView> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.success(auth.register(request));
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.success(auth.login(request));
    }

    @GetMapping("/me")
    public ApiResponse<UserView> me() { return ApiResponse.success(UserView.from(auth.currentUser())); }

    @PostMapping("/logout")
    public ApiResponse<Void> logout() { StpUtil.logout(); return ApiResponse.success(null); }
}
