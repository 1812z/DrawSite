package com.z1812.drawsite.application;

import com.z1812.drawsite.common.api.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import static com.z1812.drawsite.application.ApplicationDtos.*;

@RestController
@RequestMapping("/api/applications")
public class ApplicationController {
    private final ApplicationService applications;
    public ApplicationController(ApplicationService applications) { this.applications = applications; }

    @GetMapping
    public ApiResponse<PageResponse<ApplicationView>> list(@RequestParam(defaultValue = "1") @Min(1) int page,
                                                           @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(applications.list(page, size));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ApplicationView> create(@Valid @RequestBody ApplicationRequest request) {
        return ApiResponse.success(applications.create(request));
    }

    @GetMapping("/{id}")
    public ApiResponse<ApplicationView> detail(@PathVariable String id) {
        return ApiResponse.success(ApplicationView.from(applications.requireOwned(id)));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        applications.delete(id);
        return ApiResponse.success(null);
    }

    @PutMapping("/{id}")
    public ApiResponse<ApplicationView> update(@PathVariable String id, @Valid @RequestBody ApplicationRequest request) {
        return ApiResponse.success(applications.update(id, request));
    }

    @GetMapping("/{id}/revisions")
    public ApiResponse<PageResponse<RevisionEntity>> revisions(@PathVariable String id,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(applications.revisions(id, page, size));
    }

    @GetMapping("/{id}/documents")
    public ApiResponse<DocumentView> document(@PathVariable String id, @RequestParam String path) throws java.io.IOException {
        return ApiResponse.success(applications.document(id, path));
    }
}
