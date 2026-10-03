package com.z1812.drawsite.conversation;

import com.z1812.drawsite.application.ApplicationService;
import com.z1812.drawsite.agent.runtime.AgentRunMapper;
import com.z1812.drawsite.agent.runtime.StopAgentEvent;
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
    private final AgentRunMapper runs;
    private final org.springframework.context.ApplicationEventPublisher events;
    public ConversationService(ApplicationService applications, ConversationMapper conversations, MessageMapper messages,
                               AgentRunMapper runs, org.springframework.context.ApplicationEventPublisher events) {
        this.applications = applications;
        this.conversations = conversations;
        this.messages = messages;
        this.runs = runs;
        this.events = events;
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

    @Transactional
    public ConversationEntity create(String applicationId, String title) {
        applications.lockOwned(applicationId);
        var conversation = new ConversationEntity();
        conversation.setId(UUID.randomUUID().toString());
        conversation.setApplicationId(applicationId);
        conversation.setTitle(title.strip());
        conversation.setCreatedAt(OffsetDateTime.now());
        conversation.setUpdatedAt(conversation.getCreatedAt());
        conversations.insert(conversation);
        return conversation;
    }

    @Transactional
    public ConversationEntity rename(String applicationId, String id, String title) {
        lockOwned(applicationId, id);
        conversations.rename(id, title.strip());
        return conversations.selectOneById(id);
    }

    @Transactional
    public void delete(String applicationId, String id) {
        lockOwned(applicationId, id);
        events.publishEvent(new StopAgentEvent(applicationId, id));
        // 先删除任务外键引用，消息随会话级联删除
        runs.deleteConversationRuns(id);
        conversations.deleteById(id);
    }

    private ConversationEntity lockOwned(String applicationId, String id) {
        applications.lockOwned(applicationId);
        var conversation = conversations.lock(id);
        if (conversation == null || !applicationId.equals(conversation.getApplicationId())) {
            throw new BusinessException("CONVERSATION_NOT_FOUND", "会话不存在", HttpStatus.NOT_FOUND);
        }
        return conversation;
    }

    public PageResponse<MessageEntity> messages(String applicationId, String id, int page, int size) {
        requireOwned(applicationId, id);
        return new PageResponse<>(messages.list(id, size, (long) (page - 1) * size), messages.count(id), page, size);
    }

    @Transactional
    public MessageEntity addMessage(String applicationId, String id, String content) {
        var conversation = lockOwned(applicationId, id);
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
