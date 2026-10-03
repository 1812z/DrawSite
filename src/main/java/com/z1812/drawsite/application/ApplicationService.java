package com.z1812.drawsite.application;

import cn.dev33.satoken.stp.StpUtil;
import com.z1812.drawsite.common.api.PageResponse;
import com.z1812.drawsite.common.exception.BusinessException;
import com.z1812.drawsite.workspace.WorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.OffsetDateTime;
import java.util.UUID;
import static com.z1812.drawsite.application.ApplicationDtos.*;

@Service
public class ApplicationService {
    private static final Logger log = LoggerFactory.getLogger(ApplicationService.class);
    private final ApplicationMapper applications;
    private final RevisionMapper revisions;
    private final WorkspaceService workspace;
    private final TransactionTemplate transactions;

    public ApplicationService(ApplicationMapper applications, RevisionMapper revisions,
                              WorkspaceService workspace, TransactionTemplate transactions) {
        this.applications = applications;
        this.revisions = revisions;
        this.workspace = workspace;
        this.transactions = transactions;
    }

    public ApplicationEntity requireOwned(String id) {
        var application = applications.findOwned(id, StpUtil.getLoginIdAsString());
        if (application == null) throw new BusinessException("APPLICATION_NOT_FOUND", "应用不存在", HttpStatus.NOT_FOUND);
        return application;
    }

    public PageResponse<ApplicationView> list(int page, int size) {
        String owner = StpUtil.getLoginIdAsString();
        return new PageResponse<>(applications.listOwned(owner, size, (long) (page - 1) * size).stream()
                .map(ApplicationView::from).toList(), applications.countOwned(owner), page, size);
    }

    public ApplicationView create(ApplicationRequest request) {
        var application = new ApplicationEntity();
        application.setId(UUID.randomUUID().toString());
        application.setOwnerUserId(StpUtil.getLoginIdAsString());
        application.setName(request.name().strip());
        application.setDescription(request.description() == null ? "" : request.description().strip());
        application.setStatus("INITIALIZING");
        application.setCreatedAt(OffsetDateTime.now());
        application.setUpdatedAt(application.getCreatedAt());
        applications.insert(application);
        try {
            String commit = workspace.initialize(application.getId());
            transactions.executeWithoutResult(status -> {
                application.setCurrentCommit(commit);
                application.setStatus("READY");
                application.setUpdatedAt(OffsetDateTime.now());
                applications.update(application);
                var revision = new RevisionEntity();
                revision.setId(UUID.randomUUID().toString());
                revision.setApplicationId(application.getId());
                revision.setCommitHash(commit);
                revision.setSummary("初始化项目文档");
                revision.setCreatedAt(OffsetDateTime.now());
                revisions.insert(revision);
            });
        } catch (Exception exception) {
            log.error("Application initialization failed: {}", application.getId(), exception);
            var failed = new ApplicationEntity();
            failed.setId(application.getId());
            failed.setStatus("FAILED");
            failed.setUpdatedAt(OffsetDateTime.now());
            applications.update(failed);
            throw new BusinessException("WORKSPACE_INIT_FAILED", "项目初始化失败，可在应用列表查看失败记录", HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return ApplicationView.from(application);
    }

    public ApplicationView update(String id, ApplicationRequest request) {
        var application = requireOwned(id);
        application.setName(request.name().strip());
        application.setDescription(request.description() == null ? "" : request.description().strip());
        application.setUpdatedAt(OffsetDateTime.now());
        applications.update(application);
        return ApplicationView.from(application);
    }

    public PageResponse<RevisionEntity> revisions(String id, int page, int size) {
        requireOwned(id);
        return new PageResponse<>(revisions.list(id, size, (long) (page - 1) * size), revisions.count(id), page, size);
    }

    public DocumentView document(String id, String path) throws java.io.IOException {
        var application = requireOwned(id);
        if (!"READY".equals(application.getStatus())) throw new BusinessException("APPLICATION_NOT_READY", "应用工作区尚未就绪", HttpStatus.CONFLICT);
        return new DocumentView(path, workspace.readDocument(id, path));
    }
}
