package com.z1812.drawsite.agent;

import com.z1812.drawsite.agent.framework.ToolChatRunner;
import com.z1812.drawsite.agent.runtime.RunStep;
import com.z1812.drawsite.tool.framework.ProjectToolRegistry;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.*;
import org.junit.jupiter.api.Test;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ToolChatRunnerTests {
    @Test
    void disconnectedStreamDoesNotWriteJson() {
        var response = mock(jakarta.servlet.http.HttpServletResponse.class);
        var handler = new com.z1812.drawsite.common.exception.GlobalExceptionHandler();
        handler.handleDisconnected(new org.springframework.web.context.request.async.AsyncRequestNotUsableException("closed"));
        when(response.getContentType()).thenReturn("text/event-stream");
        assertNull(handler.handleUnexpected(new IllegalStateException("stream ended"), response));
        verify(response, never()).setStatus(anyInt());
    }

    @Test
    void multipleToolRoundsKeepFinalResultSeparate() throws Exception {
        var model = mock(StreamingChatModel.class);
        var tools = mock(ProjectToolRegistry.Session.class);
        var observer = mock(ToolChatRunner.Observer.class);
        var handler = mock(StreamingChatResponseHandler.class);
        var done = new CountDownLatch(1);
        var round = new java.util.concurrent.atomic.AtomicInteger();
        when(tools.specifications()).thenReturn(List.of());
        when(tools.execute(any())).thenReturn("{\"code\":\"OK\"}");
        doAnswer(invocation -> {
            StreamingChatResponseHandler callback = invocation.getArgument(1);
            int number = round.incrementAndGet();
            callback.onPartialThinking(new PartialThinking("思考"));
            callback.onPartialResponse(number < 3 ? "中间说明" : "最终结果");
            AiMessage message = number < 3
                    ? AiMessage.from("中间说明", List.of(ToolExecutionRequest.builder().id("call" + number)
                    .name("readProjectFile").arguments("{}").build()))
                    : AiMessage.from("最终结果");
            callback.onCompleteResponse(ChatResponse.builder().aiMessage(message).build());
            return null;
        }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        doAnswer(invocation -> { done.countDown(); return null; }).when(handler).onCompleteResponse(any());
        var runner = new ToolChatRunner();
        try {
            runner.start(model, List.of(UserMessage.from("开发")), tools, handler, () -> false, observer);
            assertTrue(done.await(5, TimeUnit.SECONDS));
            verify(tools, times(2)).execute(any());
            verify(observer).waiting(3);
            verify(observer, times(2)).roundFinished(true);
            verify(observer).roundFinished(false);
            verify(handler, times(3)).onPartialThinking(any(PartialThinking.class));
            verify(handler).onCompleteResponse(argThat(response -> "最终结果".equals(response.aiMessage().text())));
        } finally { runner.shutdown(); }
    }

    @Test
    void processSurvivesSerialization() {
        var steps = List.of(new RunStep("step", 2, "TOOL", "读取文件", "SUCCEEDED", "结果", "readProjectFile",
                OffsetDateTime.now(), OffsetDateTime.now()));
        assertEquals(steps, RunStep.decode(RunStep.encode(steps)));
    }
}
