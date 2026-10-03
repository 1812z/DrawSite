package com.z1812.drawsite.auth;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.Table;
import lombok.Data;
import java.time.OffsetDateTime;

@Data
@Table("sys_user")
public class UserEntity {
    @Id
    private String id;
    private String username;
    private String passwordHash;
    private String displayName;
    private String role;
    private Boolean enabled;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
