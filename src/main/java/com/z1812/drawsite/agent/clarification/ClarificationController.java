package com.z1812.drawsite.agent.clarification;

import com.z1812.drawsite.common.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/applications/{applicationId}/conversations/{conversationId}/clarifications")
public class ClarificationController {
    private final ClarificationService service;
    public ClarificationController(ClarificationService service) { this.service = service; }
    @GetMapping
    public ApiResponse<List<ClarificationDtos.View>> list(@PathVariable String applicationId, @PathVariable String conversationId) {
        return ApiResponse.success(service.list(applicationId, conversationId));
    }
    @PostMapping("/{id}/answers")
    public ApiResponse<ClarificationDtos.View> answer(@PathVariable String applicationId, @PathVariable String conversationId,
                                                    @PathVariable String id, @Valid @RequestBody ClarificationDtos.Reply reply) {
        return ApiResponse.success(service.reply(applicationId, conversationId, id, reply));
    }
}
