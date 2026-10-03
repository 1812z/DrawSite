package com.z1812.drawsite.tool.builtin;

import com.z1812.drawsite.agent.clarification.ClarificationService;
import com.z1812.drawsite.agent.clarification.ClarificationDtos.Question;
import com.z1812.drawsite.tool.framework.*;
import dev.langchain4j.agent.tool.*;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class ClarificationToolModule implements ProjectToolModule {
    private final ClarificationService service;
    public ClarificationToolModule(ClarificationService service) { this.service = service; }
    @Override public String id() { return "clarification"; }
    @Override public String name() { return "询问细节"; }
    @Override public String description() { return "向用户询问细节，等待全部问题回答后继续"; }
    @Override public Object bind(ToolContext context) { return new Questions(service, context); }

    public record Questions(ClarificationService service, ToolContext context) {
        @Tool("向用户询问1至3个必要问题，每个问题含question文本及options数组（0至5个候选项）。用户可多选或自定义输入。调用后暂停，收到全部问题答案才继续。不要自行猜测用户答案")
        public Object askDetails(@P("1至3个问题，每题包含question和options，options可为空数组") List<Question> questions) {
            return service.ask(context, questions);
        }
    }
}
