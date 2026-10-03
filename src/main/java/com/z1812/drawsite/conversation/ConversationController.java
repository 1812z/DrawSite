package com.z1812.drawsite.conversation;

import com.z1812.drawsite.common.api.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/applications/{applicationId}/conversations")
public class ConversationController {
    private final ConversationService conversations;
    public ConversationController(ConversationService conversations) { this.conversations = conversations; }
    public record TitleRequest(@NotBlank @Size(max = 100) String title) {}
    public record MessageRequest(@NotBlank @Size(max = 20000) String content) {}

    @GetMapping
    public ApiResponse<PageResponse<ConversationEntity>> list(@PathVariable String applicationId,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(conversations.list(applicationId, page, size));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ConversationEntity> create(@PathVariable String applicationId, @Valid @RequestBody TitleRequest request) {
        return ApiResponse.success(conversations.create(applicationId, request.title()));
    }

    @PutMapping("/{id}")
    public ApiResponse<ConversationEntity> rename(@PathVariable String applicationId, @PathVariable String id,
                                                 @Valid @RequestBody TitleRequest request) {
        return ApiResponse.success(conversations.rename(applicationId, id, request.title()));
    }

    @GetMapping("/{id}/messages")
    public ApiResponse<PageResponse<MessageEntity>> messages(@PathVariable String applicationId, @PathVariable String id,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size) {
        return ApiResponse.success(conversations.messages(applicationId, id, page, size));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String applicationId, @PathVariable String id) {
        conversations.delete(applicationId, id);
        return ApiResponse.success(null);
    }

    @PostMapping("/{id}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<MessageEntity> addMessage(@PathVariable String applicationId, @PathVariable String id,
                                                @Valid @RequestBody MessageRequest request) {
        return ApiResponse.success(conversations.addMessage(applicationId, id, request.content()));
    }
}
