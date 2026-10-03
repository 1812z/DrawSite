package com.z1812.drawsite.application;

import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface ApplicationMapper extends BaseMapper<ApplicationEntity> {
    @Select("SELECT * FROM application WHERE owner_user_id = #{owner} ORDER BY updated_at DESC, id LIMIT #{size} OFFSET #{offset}")
    List<ApplicationEntity> listOwned(@Param("owner") String owner, @Param("size") int size, @Param("offset") long offset);

    @Select("SELECT COUNT(*) FROM application WHERE owner_user_id = #{owner}")
    long countOwned(String owner);

    @Select("SELECT * FROM application WHERE id = #{id} AND owner_user_id = #{owner}")
    ApplicationEntity findOwned(@Param("id") String id, @Param("owner") String owner);

    @Select("SELECT * FROM application WHERE id = #{id} AND owner_user_id = #{owner} FOR UPDATE")
    ApplicationEntity lockOwned(@Param("id") String id, @Param("owner") String owner);

    @Select("SELECT COUNT(*) FROM agent_run WHERE application_id = #{id} AND status = 'RUNNING'")
    long activeRuns(String id);

    @Select("SELECT * FROM application WHERE id = #{id} FOR UPDATE")
    ApplicationEntity lockById(String id);

    @Delete("DELETE FROM agent_run WHERE application_id = #{id}")
    int deleteRuns(String id);

    @Update("UPDATE agent_run SET status = 'CANCELLED', finished_at = CURRENT_TIMESTAMP WHERE application_id = #{id} AND status = 'RUNNING'")
    int cancelRuns(String id);

    @Delete("DELETE FROM agent_memory WHERE application_id = #{id}")
    int deleteMemories(String id);
}
