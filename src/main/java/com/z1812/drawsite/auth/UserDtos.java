package com.z1812.drawsite.auth;

import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;

public final class UserDtos {
    private UserDtos() {}

    public record ProfileRequest(
            @NotBlank @Pattern(regexp = "[a-zA-Z0-9_]{3,32}") String username,
            @NotBlank @Size(max = 64) String displayName) {}

    public record PasswordRequest(@NotBlank @Size(max = 72) String oldPassword,
                                  @NotBlank @Size(min = 8, max = 72) String newPassword) {}

    public record AdminUpdateRequest(
            @NotBlank @Pattern(regexp = "[a-zA-Z0-9_]{3,32}") String username,
            @NotBlank @Size(max = 64) String displayName,
            @NotBlank @Pattern(regexp = "USER|ADMIN") String role,
            @NotNull Boolean enabled) {}

    public record ResetPasswordRequest(@NotBlank @Size(min = 8, max = 72) String newPassword) {}

    public record AdminUserView(String id, String username, String displayName, String role,
                                boolean enabled, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        public static AdminUserView from(UserEntity user) {
            return new AdminUserView(user.getId(), user.getUsername(), user.getDisplayName(), user.getRole(),
                    Boolean.TRUE.equals(user.getEnabled()), user.getCreatedAt(), user.getUpdatedAt());
        }
    }
}
