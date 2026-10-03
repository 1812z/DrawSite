package com.z1812.drawsite.application;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.Table;
import lombok.Data;
import java.time.OffsetDateTime;

@Data
@Table("application_revision")
public class RevisionEntity {
    @Id
    private String id;
    private String applicationId;
    private String commitHash;
    private String summary;
    private OffsetDateTime createdAt;
}
