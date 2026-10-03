package com.z1812.drawsite.agent;

import com.mybatisflex.core.BaseMapper;
import com.z1812.drawsite.conversation.MessageEntity;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface AgentRunMapper extends BaseMapper<AgentRun> {
    @Select("SELECT * FROM agent_run WHERE conversation_id = #{conversationId} ORDER BY created_at DESC, id LIMIT 1")
    AgentRun latest(String conversationId);

    @Select("SELECT m.* FROM conversation_message m JOIN agent_run r ON m.id = r.user_message_id OR m.id = r.assistant_message_id "
            + "WHERE r.conversation_id = #{conversationId} AND r.status = 'SUCCEEDED' ORDER BY r.created_at DESC, CASE WHEN m.role = 'ASSISTANT' THEN 0 ELSE 1 END LIMIT 40")
    List<MessageEntity> recentHistory(String conversationId);

    @Select("SELECT COUNT(*) FROM agent_run WHERE conversation_id = #{conversationId} AND status = 'SUCCEEDED'")
    long countSucceeded(String conversationId);

    @Update("UPDATE agent_run SET status = 'FAILED', error_code = 'SERVER_RESTARTED', finished_at = CURRENT_TIMESTAMP WHERE status = 'RUNNING'")
    int recoverInterrupted();
}
