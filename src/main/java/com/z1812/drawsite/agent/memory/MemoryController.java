package com.z1812.drawsite.agent.memory;

import cn.dev33.satoken.stp.StpUtil;
import com.z1812.drawsite.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users/me/memory")
public class MemoryController {
    private final MemoryMapper memories;
    public MemoryController(MemoryMapper memories) { this.memories = memories; }
    public record Request(@NotNull @Size(max = 4000) String content) {}
    @GetMapping
    public ApiResponse<MemoryMapper.Memory> get() {
        var memory = memories.get(StpUtil.getLoginIdAsString());
        return ApiResponse.success(memory == null ? new MemoryMapper.Memory("", null) : memory);
    }
    @PutMapping
    public ApiResponse<MemoryMapper.Memory> save(@Valid @RequestBody Request request) {
        String owner = StpUtil.getLoginIdAsString();
        memories.save(owner, request.content().strip());
        return ApiResponse.success(memories.get(owner));
    }
    @DeleteMapping
    public ApiResponse<Void> clear() {
        memories.clear(StpUtil.getLoginIdAsString());
        return ApiResponse.success(null);
    }
}
