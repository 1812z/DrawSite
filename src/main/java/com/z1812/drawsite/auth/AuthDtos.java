package com.z1812.drawsite.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class AuthDtos {
    private AuthDtos() {}

    public record RegisterRequest(
            @NotBlank @Pattern(regexp = "[a-zA-Z0-9_]{3,32}", message = "应为3至32位字母、数字或下划线") String username,
            @NotBlank @Size(min = 8, max = 72) String password,
            @NotBlank @Size(max = 64) String displayName) {}

    public record LoginRequest(@NotBlank @Size(max = 32) String username,
                               @NotBlank @Size(max = 72) String password) {}

    public record UserView(String id, String username, String displayName, String role) {
        public static UserView from(UserEntity user) {
            return new UserView(user.getId(), user.getUsername(), user.getDisplayName(), user.getRole());
        }
    }

    public record LoginResponse(String token, String tokenType, long expiresIn, UserView user) {}
}
