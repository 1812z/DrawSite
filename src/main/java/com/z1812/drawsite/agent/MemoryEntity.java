package com.z1812.drawsite.agent;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.Table;
import lombok.Data;
import java.time.OffsetDateTime;

@Data
@Table("agent_memory")
public class MemoryEntity {
    @Id
    private String id;
    private String ownerUserId;
    private String applicationId;
    private String scope;
    private String content;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
