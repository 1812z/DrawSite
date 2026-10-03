package com.z1812.drawsite.agent.clarification;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.Table;
import lombok.Data;
import java.time.OffsetDateTime;

@Data
@Table("agent_clarification")
public class ClarificationEntity {
    @Id private String id;
    private String runId;
    private String conversationId;
    private String questionsJson;
    private String answersJson;
    private String status;
    private OffsetDateTime createdAt;
    private OffsetDateTime answeredAt;
}
