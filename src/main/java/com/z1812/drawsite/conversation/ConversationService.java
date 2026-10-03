package com.z1812.drawsite.conversation;

import com.z1812.drawsite.application.ApplicationService;
import com.z1812.drawsite.common.api.PageResponse;
import com.z1812.drawsite.common.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.util.UUID;

@Service
public class ConversationService {
    private final ApplicationService applications;
    private final ConversationMapper conversations;
    private final MessageMapper messages;
    public ConversationService(ApplicationService applications, ConversationMapper conversations, MessageMapper messages) {
        this.applications = applications;
        this.conversations = conversations;
        this.messages = messages;
    }

    public ConversationEntity requireOwned(String applicationId, String conversationId) {
        applications.requireOwned(applicationId);
        var conversation = conversations.selectOneById(conversationId);
        if (conversation == null || !applicationId.equals(conversation.getApplicationId())) {
            throw new BusinessException("CONVERSATION_NOT_FOUND", "会话不存在", HttpStatus.NOT_FOUND);
        }
        return conversation;
    }

    public PageResponse<ConversationEntity> list(String applicationId, int page, int size) {
        applications.requireOwned(applicationId);
        return new PageResponse<>(conversations.list(applicationId, size, (long) (page - 1) * size), conversations.count(applicationId), page, size);
    }

    public ConversationEntity create(String applicationId, String title) {
        applications.requireOwned(applicationId);
        var conversation = new ConversationEntity();
        conversation.setId(UUID.randomUUID().toString());
        conversation.setApplicationId(applicationId);
        conversation.setTitle(title.strip());
        conversation.setCreatedAt(OffsetDateTime.now());
        conversation.setUpdatedAt(conversation.getCreatedAt());
        conversations.insert(conversation);
        return conversation;
    }

    public ConversationEntity rename(String applicationId, String id, String title) {
        var conversation = requireOwned(applicationId, id);
        conversation.setTitle(title.strip());
        conversation.setUpdatedAt(OffsetDateTime.now());
        conversations.update(conversation);
        return conversation;
    }

    public PageResponse<MessageEntity> messages(String applicationId, String id, int page, int size) {
        requireOwned(applicationId, id);
        return new PageResponse<>(messages.list(id, size, (long) (page - 1) * size), messages.count(id), page, size);
    }

    @Transactional
    public MessageEntity addMessage(String applicationId, String id, String content) {
        var conversation = requireOwned(applicationId, id);
        var message = new MessageEntity();
        message.setId(UUID.randomUUID().toString());
        message.setConversationId(id);
        message.setRole("USER");
        message.setContent(content);
        message.setCreatedAt(OffsetDateTime.now());
        messages.insert(message);
        conversation.setUpdatedAt(message.getCreatedAt());
        conversations.update(conversation);
        return message;
    }
}
