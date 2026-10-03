package com.z1812.drawsite.application;

import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface RevisionMapper extends BaseMapper<RevisionEntity> {
    @Select("SELECT * FROM application_revision WHERE application_id = #{applicationId} ORDER BY created_at DESC, id LIMIT #{size} OFFSET #{offset}")
    List<RevisionEntity> list(@Param("applicationId") String applicationId, @Param("size") int size, @Param("offset") long offset);

    @Select("SELECT COUNT(*) FROM application_revision WHERE application_id = #{applicationId}")
    long count(String applicationId);
}
