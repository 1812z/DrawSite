package com.z1812.drawsite.application;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

public final class ApplicationDtos {
    private ApplicationDtos() {}
    public record ApplicationRequest(@NotBlank @Size(max = 100) String name,
                                     @Size(max = 2000) String description) {}
    public record ApplicationView(String id, String name, String description, String status,
                                  String currentCommit, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        public static ApplicationView from(ApplicationEntity entity) {
            return new ApplicationView(entity.getId(), entity.getName(), entity.getDescription(), entity.getStatus(),
                    entity.getCurrentCommit(), entity.getCreatedAt(), entity.getUpdatedAt());
        }
    }
    public record DocumentView(String path, String content) {}
}
