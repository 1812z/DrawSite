package com.z1812.drawsite.agent.memory;

import org.apache.ibatis.annotations.*;
import java.time.OffsetDateTime;

@Mapper
public interface MemoryMapper {
    record Memory(String content, OffsetDateTime updatedAt) {}
    @Select("SELECT content, updated_at FROM user_memory WHERE owner_user_id = #{owner}")
    Memory get(String owner);
    @Insert("INSERT INTO user_memory(owner_user_id, content, updated_at) VALUES (#{owner}, #{content}, CURRENT_TIMESTAMP) "
            + "ON CONFLICT (owner_user_id) DO UPDATE SET content = EXCLUDED.content, updated_at = EXCLUDED.updated_at")
    int save(@Param("owner") String owner, @Param("content") String content);
    @Delete("DELETE FROM user_memory WHERE owner_user_id = #{owner}")
    int clear(String owner);
    @Insert("INSERT INTO user_memory(owner_user_id, content, updated_at) VALUES (#{owner}, #{content}, CURRENT_TIMESTAMP) ON CONFLICT (owner_user_id) DO NOTHING")
    int createIfAbsent(@Param("owner") String owner, @Param("content") String content);
    @Update("UPDATE user_memory SET content = #{content}, updated_at = CURRENT_TIMESTAMP WHERE owner_user_id = #{owner} AND content = #{expected}")
    int replaceIfUnchanged(@Param("owner") String owner, @Param("content") String content, @Param("expected") String expected);
}
