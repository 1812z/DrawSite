package com.z1812.drawsite.agent.runtime;

import com.mybatisflex.core.BaseMapper;
import com.z1812.drawsite.conversation.MessageEntity;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface AgentRunMapper extends BaseMapper<AgentRun> {
    @Select("SELECT * FROM agent_run WHERE conversation_id = #{conversationId} ORDER BY created_at DESC, id LIMIT 1")
    AgentRun latest(String conversationId);

    @Select("SELECT * FROM agent_run WHERE conversation_id=#{id} AND status IN ('SUCCEEDED','FAILED','CANCELLED') AND (#{memory} OR memory_enabled=FALSE) ORDER BY created_at,id")
    List<AgentRun> contextHistory(@Param("id") String id, @Param("memory") boolean memory);

    @Select("SELECT COUNT(*) FROM agent_run WHERE conversation_id = #{conversationId} AND status = 'SUCCEEDED'")
    long countSucceeded(String conversationId);

    @Update("UPDATE agent_run SET status = 'FAILED', error_code = 'SERVER_RESTARTED', finished_at = CURRENT_TIMESTAMP WHERE status = 'RUNNING'")
    int recoverInterrupted();

    @Update("UPDATE agent_run SET process_json = #{process} WHERE id = #{id} AND status = 'RUNNING'")
    int saveProcess(@Param("id") String id, @Param("process") String process);

    @Delete("DELETE FROM agent_run WHERE conversation_id = #{conversationId}")
    int deleteConversationRuns(String conversationId);
}
