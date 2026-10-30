package com.z1812.drawsite.agent.framework;

import com.z1812.drawsite.agent.runtime.AgentDtos;

import com.z1812.drawsite.common.exception.BusinessException;
import com.z1812.drawsite.settings.SettingsDtos.ModelConfig;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import java.time.Duration;

@Component
public class AgentModelFactory {
    public String credential(ModelConfig model) {
        String key = model.apiKey();
        if (key == null || key.isBlank()) throw new BusinessException("MODEL_CREDENTIAL_MISSING", "模型凭据尚未配置", HttpStatus.CONFLICT);
        return key;
    }

    public OpenAiStreamingChatModel streaming(ModelConfig model, String key, AgentDtos.ReasoningEffort effort) {
        var builder = OpenAiStreamingChatModel.builder().baseUrl(model.baseUrl()).apiKey(key)
                .modelName(model.modelName()).timeout(Duration.ofSeconds(model.timeoutSeconds()))
                .returnThinking(true).logRequests(false).logResponses(false);
        if (Boolean.TRUE.equals(model.supportsReasoning())) {
            if (effort != AgentDtos.ReasoningEffort.DEFAULT) builder.reasoningEffort(effort.name().toLowerCase(java.util.Locale.ROOT));
        } else builder.temperature(model.temperature());
        return builder.build();
    }

    public OpenAiChatModel titleModel(ModelConfig model, String key) {
        return OpenAiChatModel.builder().baseUrl(model.baseUrl()).apiKey(key).modelName(model.modelName())
                .timeout(Duration.ofSeconds(Math.min(20, model.timeoutSeconds()))).maxRetries(0)
                .logRequests(false).logResponses(false).build();
    }

    public OpenAiChatModel contextModel(ModelConfig model, String key) {
        return OpenAiChatModel.builder().baseUrl(model.baseUrl()).apiKey(key).modelName(model.modelName())
                .timeout(Duration.ofSeconds(model.timeoutSeconds())).maxRetries(0).maxCompletionTokens(6000)
                .logRequests(false).logResponses(false).build();
    }
}
