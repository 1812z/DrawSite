package com.z1812.drawsite.agent;

import cn.dev33.satoken.stp.StpUtil;
import com.z1812.drawsite.application.ApplicationService;
import com.z1812.drawsite.common.api.PageResponse;
import com.z1812.drawsite.common.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.time.OffsetDateTime;
import java.util.UUID;

@Service
public class MemoryService {
    private final ApplicationService applications;
    private final MemoryMapper memories;
    public MemoryService(ApplicationService applications, MemoryMapper memories) {
        this.applications = applications; this.memories = memories;
    }
    public PageResponse<MemoryEntity> list(String applicationId, int page, int size) {
        applications.requireOwned(applicationId);
        String owner = StpUtil.getLoginIdAsString();
        return new PageResponse<>(memories.list(owner, applicationId, size, (long) (page - 1) * size),
                memories.count(owner, applicationId), page, size);
    }
    public MemoryEntity create(String applicationId, AgentDtos.MemoryRequest request) {
        applications.requireOwned(applicationId);
        var memory = new MemoryEntity();
        memory.setId(UUID.randomUUID().toString());
        memory.setOwnerUserId(StpUtil.getLoginIdAsString());
        memory.setScope(request.scope());
        memory.setApplicationId("APPLICATION".equals(request.scope()) ? applicationId : null);
        memory.setContent(request.content().strip());
        memory.setCreatedAt(OffsetDateTime.now()); memory.setUpdatedAt(memory.getCreatedAt());
        memories.insert(memory);
        return memory;
    }
    public MemoryEntity update(String applicationId, String id, AgentDtos.MemoryRequest request) {
        var memory = requireOwned(applicationId, id);
        if (!memory.getScope().equals(request.scope())) throw new BusinessException("VALIDATION_ERROR", "记忆作用域不能修改", HttpStatus.BAD_REQUEST);
        memory.setContent(request.content().strip()); memory.setUpdatedAt(OffsetDateTime.now());
        memories.update(memory); return memory;
    }
    public void delete(String applicationId, String id) {
        requireOwned(applicationId, id); memories.deleteById(id);
    }
    private MemoryEntity requireOwned(String applicationId, String id) {
        applications.requireOwned(applicationId);
        var memory = memories.selectOneById(id);
        if (memory == null || !memory.getOwnerUserId().equals(StpUtil.getLoginIdAsString())
                || (memory.getApplicationId() != null && !memory.getApplicationId().equals(applicationId))) {
            throw new BusinessException("MEMORY_NOT_FOUND", "记忆不存在", HttpStatus.NOT_FOUND);
        }
        return memory;
    }
}
