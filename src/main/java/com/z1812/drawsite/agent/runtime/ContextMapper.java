package com.z1812.drawsite.agent.runtime;

import org.apache.ibatis.annotations.*;

@Mapper
public interface ContextMapper {
    record Checkpoint(String coveredRunId, String summary) {}
    // 统一复用包含全部历史的摘要；旧关闭模式摘要可能漏轮次，不作为回退。
    @Select("SELECT covered_run_id, summary FROM conversation_context WHERE conversation_id=#{id} AND memory_enabled=TRUE")
    Checkpoint get(@Param("id") String id, @Param("memory") boolean memory);
    @Insert("INSERT INTO conversation_context(conversation_id,memory_enabled,covered_run_id,summary) VALUES(#{id},TRUE,#{run},#{summary}) "
            + "ON CONFLICT(conversation_id,memory_enabled) DO UPDATE SET covered_run_id=EXCLUDED.covered_run_id,summary=EXCLUDED.summary,updated_at=CURRENT_TIMESTAMP")
    void save(@Param("id") String id, @Param("memory") boolean memory, @Param("run") String run, @Param("summary") String summary);
}
