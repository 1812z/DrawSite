package com.z1812.drawsite.agent.runtime;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.Table;
import lombok.Data;
import java.time.OffsetDateTime;

@Data
@Table("agent_run")
public class AgentRun {
    @Id
    private String id;
    private String applicationId;
    private String conversationId;
    private String userMessageId;
    private String assistantMessageId;
    private String modelId;
    private String modelName;
    private String reasoningEffort;
    private Boolean memoryEnabled;
    private String status;
    private String errorCode;
    private String processJson;
    private OffsetDateTime createdAt;
    private OffsetDateTime finishedAt;
}
