package com.z1812.drawsite.docker;

import com.mybatisflex.annotation.*;
import lombok.Data;
import java.time.OffsetDateTime;

@Data
@Table("application_deployment")
public class DeploymentEntity {
    @Id private String id;
    private String ownerUserId;
    private String containerId;
    private String applicationId;
    private String type;
    private String directory;
    private String url;
    private String status;
    private String error;
    private OffsetDateTime createdAt;
}
