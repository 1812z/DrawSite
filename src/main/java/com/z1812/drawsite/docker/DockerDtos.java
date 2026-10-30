package com.z1812.drawsite.docker;

import jakarta.validation.constraints.*;
import java.util.List;

public final class DockerDtos {
    private DockerDtos() {}
    public record Config(@Min(1) @Max(100) int maxRunning, @Min(1) @Max(100) int maxContainers,
                         @Min(1) @Max(1000) int maxPlatformRunning,
                         @Min(64) @Max(1048576) int diskMiB, @Min(64) @Max(65536) int memoryMiB,
                         @DecimalMin("0.1") @DecimalMax("64") double cpus,
                         @Min(16) @Max(4096) int pids, @Min(1) @Max(1440) int temporaryMinutes,
                         @NotBlank String publicHost,
                         @Min(40001) @Max(65534) int portStart, @Min(40002) @Max(65535) int portEnd) {
        public static Config defaults() { return new Config(5, 5, 20, 1024, 512, 1, 128, 15,
                "localhost", 40001, 49999); }
    }
    public record ConfigView(Config config) {}
    public record Template(@NotBlank @Pattern(regexp = "[a-zA-Z0-9_.-]{1,64}") String id,
                           @NotBlank @Size(max = 100) String name,
                           @NotBlank @Pattern(regexp = "[a-zA-Z0-9][a-zA-Z0-9_./:@-]{0,250}") String image,
                           @NotNull @Size(min = 1, max = 30) List<@NotBlank @Size(max = 2000) String> command,
                           @Min(1) @Max(65535) int internalPort,
                           @NotBlank @Size(max = 200) String healthPath, boolean enabled,
                           @Size(max = 4000) String instructions) {}
    public record Create(@NotBlank String applicationId, @NotBlank String templateId,
                         @NotBlank @Pattern(regexp = "TEMPORARY|PUBLISH") String purpose) {}
    public record Publish(@NotBlank String applicationId, @Pattern(regexp = "STATIC|SERVICE") @NotBlank String type,
                          String containerId, @Size(max = 300) String directory) {}
    public record Execute(@NotBlank @Size(max = 20000) String command, @Min(1) @Max(120) int timeoutSeconds) {}
}
