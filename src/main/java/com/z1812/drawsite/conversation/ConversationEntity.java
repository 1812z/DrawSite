package com.z1812.drawsite.conversation;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.Table;
import lombok.Data;
import java.time.OffsetDateTime;

@Data
@Table("conversation")
public class ConversationEntity {
    @Id
    private String id;
    private String applicationId;
    private String title;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
