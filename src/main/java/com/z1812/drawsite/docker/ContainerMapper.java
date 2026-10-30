package com.z1812.drawsite.docker;

import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface ContainerMapper extends BaseMapper<ContainerEntity> {
    @Select("SELECT * FROM managed_container ORDER BY created_at DESC") List<ContainerEntity> all();
    @Select("SELECT * FROM managed_container WHERE owner_user_id = #{owner} ORDER BY created_at DESC") List<ContainerEntity> owned(String owner);
    @Select("SELECT * FROM managed_container WHERE id = #{id} FOR UPDATE") ContainerEntity lock(String id);
    @Select("SELECT content FROM infrastructure_config WHERE id = 1") String config();
    @Select("SELECT content FROM infrastructure_config WHERE id = 1 FOR UPDATE") String lockConfig();
    @Insert("INSERT INTO infrastructure_config(id, content) VALUES(1, #{json}) ON CONFLICT DO NOTHING") int initConfig(String json);
    @Update("UPDATE infrastructure_config SET content = #{json} WHERE id = 1") int configSave(String json);
    @Select("SELECT content FROM docker_template ORDER BY id") List<String> templates();
    @Insert("INSERT INTO docker_template(id, content) VALUES(#{id}, #{json}) ON CONFLICT(id) DO UPDATE SET content=EXCLUDED.content")
    int templateSave(@Param("id") String id, @Param("json") String json);
    @Delete("DELETE FROM docker_template WHERE id = #{id}") int templateDelete(String id);
}
