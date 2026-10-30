package com.z1812.drawsite.agent.runtime;

import org.apache.ibatis.annotations.*;

@Mapper
public interface ContextMapper {
    record Checkpoint(String coveredRunId, String summary) {}
    @Select("SELECT covered_run_id, summary FROM conversation_context WHERE conversation_id=#{id} AND memory_enabled=#{memory}")
    Checkpoint get(@Param("id") String id, @Param("memory") boolean memory);
    @Insert("INSERT INTO conversation_context(conversation_id,memory_enabled,covered_run_id,summary) VALUES(#{id},#{memory},#{run},#{summary}) "
            + "ON CONFLICT(conversation_id,memory_enabled) DO UPDATE SET covered_run_id=EXCLUDED.covered_run_id,summary=EXCLUDED.summary,updated_at=CURRENT_TIMESTAMP")
    void save(@Param("id") String id, @Param("memory") boolean memory, @Param("run") String run, @Param("summary") String summary);
}
