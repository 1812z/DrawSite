package com.z1812.drawsite.tool.framework;

import com.z1812.drawsite.agent.runtime.AgentRunMapper;
import com.z1812.drawsite.application.ApplicationMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class ToolGuard {
    private final ApplicationMapper applications;
    private final AgentRunMapper runs;
    private final TransactionTemplate transactions;

    public ToolGuard(ApplicationMapper applications, AgentRunMapper runs, TransactionTemplate transactions) {
        this.applications = applications; this.runs = runs; this.transactions = transactions;
    }

    @FunctionalInterface
    public interface Action { Object execute() throws Exception; }

    public Object execute(ToolContext context, Action action) {
        return transactions.execute(status -> {
            var application = applications.lockOwned(context.applicationId(), context.ownerUserId());
            var run = runs.selectOneById(context.runId());
            if (application == null || !"READY".equals(application.getStatus()) || context.stopped().getAsBoolean()
                    || run == null || !"RUNNING".equals(run.getStatus())
                    || !context.applicationId().equals(run.getApplicationId())) {
                throw new IllegalStateException("工具任务已停止或工作区不可用");
            }
            try (var scope = com.z1812.drawsite.workspace.FileAccessScope.memory(context.memoryEnabled())) {
                return action.execute();
            }
            catch (RuntimeException exception) { throw exception; }
            catch (Exception exception) { throw new ToolOperationException(exception); }
        });
    }

    public static class ToolOperationException extends RuntimeException {
        public ToolOperationException(Exception cause) { super(cause); }
    }
}
