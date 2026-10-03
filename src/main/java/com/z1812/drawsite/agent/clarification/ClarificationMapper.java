package com.z1812.drawsite.agent.clarification;

import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface ClarificationMapper extends BaseMapper<ClarificationEntity> {
    @Select("SELECT * FROM agent_clarification WHERE conversation_id = #{id} ORDER BY created_at")
    List<ClarificationEntity> list(String id);
    @Select("SELECT * FROM agent_clarification WHERE conversation_id = #{id} AND status = 'ANSWERED' ORDER BY created_at DESC LIMIT 10")
    List<ClarificationEntity> answered(String id);
    @Select("SELECT * FROM agent_clarification WHERE run_id = #{id} AND status = 'PENDING'")
    ClarificationEntity pending(String id);
    @Update("UPDATE agent_clarification SET answers_json = #{answers}, status = 'ANSWERED', answered_at = CURRENT_TIMESTAMP WHERE id = #{id} AND status = 'PENDING'")
    int answer(@Param("id") String id, @Param("answers") String answers);
    @Update("UPDATE agent_clarification SET status = 'CANCELLED' WHERE run_id = #{id} AND status = 'PENDING'")
    int cancel(String id);
    @Update("UPDATE agent_clarification SET status = 'CANCELLED' WHERE status = 'PENDING'")
    int recover();
}
