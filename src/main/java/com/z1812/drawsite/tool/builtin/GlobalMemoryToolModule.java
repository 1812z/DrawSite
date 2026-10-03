package com.z1812.drawsite.tool.builtin;

import com.z1812.drawsite.agent.memory.MemoryMapper;
import com.z1812.drawsite.common.exception.BusinessException;
import com.z1812.drawsite.tool.framework.*;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@Component
public class GlobalMemoryToolModule implements ProjectToolModule {
    private final MemoryMapper memories;
    public GlobalMemoryToolModule(MemoryMapper memories) { this.memories = memories; }
    @Override public String id() { return "globalMemory"; }
    @Override public String name() { return "全局记忆"; }
    @Override public String description() { return "查看并编辑当前用户跨项目的全局记忆"; }
    @Override public boolean available(ToolContext context) { return context.memoryEnabled(); }
    @Override public Object bind(ToolContext context) { return new MemoryTools(memories, context); }

    public record Snapshot(String content, String sha256) {}
    public record MemoryTools(MemoryMapper memories, ToolContext context) {
        private void requireEnabled() {
            if (!context.memoryEnabled()) throw new BusinessException("MEMORY_DISABLED", "记忆已关闭", HttpStatus.FORBIDDEN);
        }
        @Tool("读取当前用户唯一一条全局记忆，返回content与sha256；无记录sha256为NEW。仅用于跨项目的稳定偏好，不保存当前项目专属信息")
        public Snapshot readGlobalMemory() {
            requireEnabled();
            var memory = memories.get(context.ownerUserId());
            return memory == null ? new Snapshot("", "NEW") : new Snapshot(memory.content(), hash(memory.content()));
        }
        @Tool("整体更新当前用户全局记忆，最多4000字符。必须先读取并提供expectedHash；保留仍有效的旧偏好。content为空表示清空内容。只记录用户明确表达的跨项目偏好，不写入密钥或项目专属决策")
        public Snapshot writeGlobalMemory(String content, String expectedHash) {
            requireEnabled();
            if (content == null || content.length() > 4000 || expectedHash == null || expectedHash.isBlank()) {
                throw new IllegalArgumentException("内容最多4000字符，必须提供读取获得的expectedHash");
            }
            String value = content.strip();
            var current = memories.get(context.ownerUserId());
            int changed;
            if (current == null) {
                if (!"NEW".equals(expectedHash)) throw conflict();
                changed = memories.createIfAbsent(context.ownerUserId(), value);
            } else {
                if (!hash(current.content()).equals(expectedHash)) throw conflict();
                changed = memories.replaceIfUnchanged(context.ownerUserId(), value, current.content());
            }
            if (changed != 1) throw conflict();
            return new Snapshot(value, hash(value));
        }
        private static BusinessException conflict() {
            return new BusinessException("MEMORY_CONFLICT", "全局记忆已变化，请重新读取后修改", HttpStatus.CONFLICT);
        }
        private static String hash(String value) {
            try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
            catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
        }
    }
}
