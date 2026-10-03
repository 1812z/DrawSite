package com.z1812.drawsite.conversation;

import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface ConversationMapper extends BaseMapper<ConversationEntity> {
    @Select("SELECT * FROM conversation WHERE application_id = #{applicationId} ORDER BY updated_at DESC, id LIMIT #{size} OFFSET #{offset}")
    List<ConversationEntity> list(@Param("applicationId") String applicationId, @Param("size") int size, @Param("offset") long offset);
    @Select("SELECT COUNT(*) FROM conversation WHERE application_id = #{applicationId}")
    long count(String applicationId);

    @Update("UPDATE conversation SET title = #{title} WHERE id = #{id} AND title = '新建会话'")
    int autoTitle(@Param("id") String id, @Param("title") String title);
}
