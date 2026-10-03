package com.z1812.drawsite.conversation;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.Table;
import lombok.Data;
import java.time.OffsetDateTime;

@Data
@Table("conversation_message")
public class MessageEntity {
    @Id
    private String id;
    private String conversationId;
    private String role;
    private String content;
    private OffsetDateTime createdAt;
}
