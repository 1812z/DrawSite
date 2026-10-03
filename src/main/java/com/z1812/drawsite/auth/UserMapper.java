package com.z1812.drawsite.auth;

import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserMapper extends BaseMapper<UserEntity> {
    @Select("SELECT * FROM sys_user WHERE username = #{username}")
    UserEntity findByUsername(String username);

    @Update("UPDATE sys_user SET password_hash = #{hash}, enabled = TRUE, updated_at = CURRENT_TIMESTAMP "
            + "WHERE id = '00000000-0000-0000-0000-000000000001' AND username = 'admin' "
            + "AND role = 'ADMIN' AND enabled = FALSE AND password_hash = 'BOOTSTRAP_PENDING'")
    int initializeAdmin(@Param("hash") String hash);
}
