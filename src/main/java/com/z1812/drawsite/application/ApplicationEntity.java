package com.z1812.drawsite.application;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.Table;
import lombok.Data;
import java.time.OffsetDateTime;

@Data
@Table("application")
public class ApplicationEntity {
    @Id
    private String id;
    private String ownerUserId;
    private String name;
    private String description;
    private String status;
    private String currentCommit;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
