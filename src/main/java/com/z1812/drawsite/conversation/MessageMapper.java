package com.z1812.drawsite.conversation;

import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface MessageMapper extends BaseMapper<MessageEntity> {
    @Select({"<script>SELECT id,content FROM conversation_message WHERE id IN",
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>"})
    List<MessageEntity> contextContents(@Param("ids") List<String> ids);
    @Select("SELECT * FROM (SELECT * FROM conversation_message WHERE conversation_id = #{conversationId} ORDER BY created_at DESC, id DESC LIMIT #{size} OFFSET #{offset}) recent ORDER BY created_at,id")
    List<MessageEntity> list(@Param("conversationId") String conversationId, @Param("size") int size, @Param("offset") long offset);
    @Select("SELECT COUNT(*) FROM conversation_message WHERE conversation_id = #{conversationId}")
    long count(String conversationId);
}
