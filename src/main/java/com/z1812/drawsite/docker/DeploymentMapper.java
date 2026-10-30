package com.z1812.drawsite.docker;

import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface DeploymentMapper extends BaseMapper<DeploymentEntity> {
    @Select("SELECT * FROM application_deployment ORDER BY created_at DESC") List<DeploymentEntity> all();
    @Select("SELECT * FROM application_deployment WHERE owner_user_id=#{owner} ORDER BY created_at DESC") List<DeploymentEntity> owned(String owner);
    @Select("SELECT * FROM application_deployment WHERE container_id=#{id}") DeploymentEntity forContainer(String id);
    @Select("SELECT * FROM application_deployment WHERE application_id=#{id} AND type='STATIC'")
    DeploymentEntity staticForApplication(String id);
}
