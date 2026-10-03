package com.z1812.drawsite.agent;

import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface MemoryMapper extends BaseMapper<MemoryEntity> {
    @Select("SELECT * FROM agent_memory WHERE owner_user_id = #{owner} AND (application_id = #{applicationId} OR scope = 'USER') ORDER BY updated_at DESC, id LIMIT #{size} OFFSET #{offset}")
    List<MemoryEntity> list(@Param("owner") String owner, @Param("applicationId") String applicationId,
                            @Param("size") int size, @Param("offset") long offset);
    @Select("SELECT COUNT(*) FROM agent_memory WHERE owner_user_id = #{owner} AND (application_id = #{applicationId} OR scope = 'USER')")
    long count(@Param("owner") String owner, @Param("applicationId") String applicationId);
}
