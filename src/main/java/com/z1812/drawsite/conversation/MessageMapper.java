package com.z1812.drawsite.conversation;

import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface MessageMapper extends BaseMapper<MessageEntity> {
    @Select("SELECT * FROM conversation_message WHERE conversation_id = #{conversationId} ORDER BY created_at, id LIMIT #{size} OFFSET #{offset}")
    List<MessageEntity> list(@Param("conversationId") String conversationId, @Param("size") int size, @Param("offset") long offset);
    @Select("SELECT COUNT(*) FROM conversation_message WHERE conversation_id = #{conversationId}")
    long count(String conversationId);
}
