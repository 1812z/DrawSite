package com.z1812.drawsite.conversation;

import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface ConversationMapper extends BaseMapper<ConversationEntity> {
    @Select("SELECT c.* FROM conversation c JOIN application a ON a.id=c.application_id WHERE c.id=#{id} AND c.application_id=#{applicationId} AND a.owner_user_id=#{owner}")
    ConversationEntity findOwned(@Param("applicationId") String applicationId, @Param("id") String id, @Param("owner") String owner);
    @Select("SELECT * FROM conversation WHERE application_id = #{applicationId} ORDER BY updated_at DESC, id LIMIT #{size} OFFSET #{offset}")
    List<ConversationEntity> list(@Param("applicationId") String applicationId, @Param("size") int size, @Param("offset") long offset);
    @Select("SELECT COUNT(*) FROM conversation WHERE application_id = #{applicationId}")
    long count(String applicationId);

    @Update("UPDATE conversation SET title = #{title} WHERE id = #{id} AND title = '新建会话'")
    int autoTitle(@Param("id") String id, @Param("title") String title);

    @Select("SELECT * FROM conversation WHERE id = #{id} FOR UPDATE")
    ConversationEntity lock(String id);

    @Update("UPDATE conversation SET updated_at = CURRENT_TIMESTAMP WHERE id = #{id}")
    int touch(String id);

    @Update("UPDATE conversation SET title = #{title}, updated_at = CURRENT_TIMESTAMP WHERE id = #{id}")
    int rename(@Param("id") String id, @Param("title") String title);
}
