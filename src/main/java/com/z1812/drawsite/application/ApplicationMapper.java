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
}
