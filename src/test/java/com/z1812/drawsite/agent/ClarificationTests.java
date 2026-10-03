package com.z1812.drawsite.agent;

import com.z1812.drawsite.agent.clarification.*;
import com.z1812.drawsite.agent.runtime.*;
import com.z1812.drawsite.tool.framework.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static com.z1812.drawsite.agent.clarification.ClarificationDtos.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ClarificationTests {
    @Test
    void toolSchemaExposesQuestionArray() {
        var module = new com.z1812.drawsite.tool.builtin.ClarificationToolModule(null);
        var specifications = dev.langchain4j.agent.tool.ToolSpecifications.toolSpecificationsFrom(
                module.bind(new ToolContext(null, null, null, () -> true)));
        assertEquals(1, specifications.size());
        assertEquals("askDetails", specifications.getFirst().name());
        assertTrue(specifications.getFirst().parameters().properties().containsKey("questions"));
    }

    @Test
    void requiresEveryQuestionAndValidOptions() {
        var questions = List.of(new Question("用途", List.of("个人", "团队")), new Question("名称", List.of()));
        ClarificationService.validateQuestions(questions);
        assertThrows(IllegalArgumentException.class, () -> ClarificationService.validateAnswers(questions,
                List.of(new Answer(0, List.of("个人"), ""))));
        assertThrows(IllegalArgumentException.class, () -> ClarificationService.validateAnswers(questions,
                List.of(new Answer(0, List.of("未知"), ""), new Answer(1, List.of(), "绘站"))));
        assertThrows(IllegalArgumentException.class, () -> ClarificationService.validateAnswers(questions,
                List.of(new Answer(0, List.of("个人"), ""), new Answer(0, List.of(), "重复"))));
        assertThrows(IllegalArgumentException.class, () -> ClarificationService.validateAnswers(questions,
                List.of(new Answer(0, List.of("个人"), ""), new Answer(1, List.of(), " "))));
        assertDoesNotThrow(() -> ClarificationService.validateAnswers(questions,
                List.of(new Answer(0, List.of("个人", "团队"), "补充"), new Answer(1, List.of(), "绘站"))));
        assertThrows(IllegalArgumentException.class, () -> ClarificationService.validateQuestions(List.of()));
        assertThrows(IllegalArgumentException.class, () -> ClarificationService.validateQuestions(
                List.of(new Question("问题", List.of("1", "2", "3", "4", "5", "6")))));
    }

    @Test
    void deferredQuestionWaitsThenResumesWithStoredAnswers() throws Exception {
        var mapper = mock(ClarificationMapper.class);
        var runs = mock(AgentRunMapper.class);
        var run = new AgentRun(); run.setId("run"); run.setConversationId("conversation");
        when(runs.selectOneById("run")).thenReturn(run);
        var stored = new AtomicReference<ClarificationEntity>();
        doAnswer(invocation -> { stored.set(invocation.getArgument(0)); return 1; }).when(mapper).insert(any(ClarificationEntity.class));
        when(mapper.selectOneById(any())).thenAnswer(invocation -> stored.get());
        var entered = new CountDownLatch(1);
        var paused = new AtomicBoolean();
        var context = new ToolContext("app", "user", "run", () -> false, value -> {
            paused.set(value); if (value) entered.countDown();
        });
        var service = new ClarificationService(mapper, runs, null, null, null);
        var deferred = service.ask(context, List.of(new Question("名称", List.of())));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = executor.submit(() -> deferred.await(context));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertTrue(paused.get());
            assertFalse(result.isDone());
            var answered = new ClarificationEntity();
            answered.setStatus("ANSWERED");
            answered.setAnswersJson("[{\"questionIndex\":0,\"selectedOptions\":[],\"customText\":\"绘站\"}]");
            stored.set(answered);
            assertTrue(result.get(3, TimeUnit.SECONDS).toString().contains("绘站"));
            assertFalse(paused.get());
        }
    }
}
