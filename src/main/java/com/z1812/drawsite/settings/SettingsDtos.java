package com.z1812.drawsite.settings;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public final class SettingsDtos {
    private SettingsDtos() {}

    public record ModelConfig(
            @NotBlank @Pattern(regexp = "[a-zA-Z0-9_-]{1,64}") String id,
            @NotBlank @Size(max = 100) String displayName,
            @NotBlank @Pattern(regexp = "OPENAI_COMPATIBLE") String provider,
            @NotBlank @Size(max = 200) String modelName,
            @NotBlank @Size(max = 1000) String baseUrl,
            @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{0,99}") String credentialRef,
            @NotNull Boolean enabled,
            @NotNull Boolean supportsTools,
            @NotNull @DecimalMin("0.0") @DecimalMax("2.0") Double temperature,
            @NotNull @Min(1) @Max(600) Integer timeoutSeconds) {}

    public record UpdateRequest(
            @NotNull Boolean registrationEnabled,
            @NotNull @Size(max = 100) List<@NotNull @Valid ModelConfig> models,
            @Size(max = 64) String defaultModelId,
            @NotNull Map<String, @NotNull @PositiveOrZero Long> versions) {}

    public record AdminSettings(boolean registrationEnabled, List<ModelConfig> models,
                                String defaultModelId, Map<String, Long> versions) {}
    public record PublicModel(String id, String displayName, boolean supportsTools) {}
    public record PublicSettings(boolean registrationEnabled, List<PublicModel> models, String defaultModelId) {}
    public record SettingRow(String settingKey, String valueJson, long version, OffsetDateTime updatedAt) {}
}
