package com.z1812.drawsite.auth;

import cn.dev33.satoken.stp.StpUtil;
import com.z1812.drawsite.common.exception.BusinessException;
import com.z1812.drawsite.settings.SettingsService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.UUID;

import static com.z1812.drawsite.auth.AuthDtos.*;

@Service
public class AuthService {
    private final UserMapper users;
    private final SettingsService settings;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final String dummyHash = encoder.encode(UUID.randomUUID().toString());

    public AuthService(UserMapper users, SettingsService settings) { this.users = users; this.settings = settings; }

    @Transactional
    public UserView register(RegisterRequest request) {
        if (!settings.isRegistrationEnabled()) {
            throw new BusinessException("REGISTRATION_DISABLED", "平台暂未开放注册", HttpStatus.FORBIDDEN);
        }
        checkPasswordBytes(request.password());
        String displayName = request.displayName().strip();
        if (displayName.isEmpty()) throw new BusinessException("VALIDATION_ERROR", "显示名称不能为空", HttpStatus.BAD_REQUEST);
        var user = new UserEntity();
        user.setId(UUID.randomUUID().toString());
        user.setUsername(request.username().toLowerCase(Locale.ROOT));
        user.setPasswordHash(encoder.encode(request.password()));
        user.setDisplayName(displayName);
        user.setRole("USER");
        user.setEnabled(true);
        user.setCreatedAt(OffsetDateTime.now());
        user.setUpdatedAt(user.getCreatedAt());
        try { users.insert(user); }
        catch (DuplicateKeyException exception) {
            throw new BusinessException("USERNAME_EXISTS", "用户名已存在", HttpStatus.CONFLICT);
        }
        return UserView.from(user);
    }

    public LoginResponse login(LoginRequest request) {
        checkPasswordBytes(request.password());
        var user = users.findByUsername(request.username().toLowerCase(Locale.ROOT));
        boolean matched = encoder.matches(request.password(), user == null ? dummyHash : user.getPasswordHash());
        if (user == null || !matched || !Boolean.TRUE.equals(user.getEnabled())) {
            throw new BusinessException("INVALID_CREDENTIALS", "用户名或密码错误", HttpStatus.UNAUTHORIZED);
        }
        StpUtil.login(user.getId());
        return new LoginResponse(StpUtil.getTokenValue(), "Bearer", StpUtil.getTokenTimeout(), UserView.from(user));
    }

    public UserEntity currentUser() {
        var user = users.selectOneById(StpUtil.getLoginIdAsString());
        if (user == null || !Boolean.TRUE.equals(user.getEnabled())) {
            StpUtil.logout();
            throw new BusinessException("UNAUTHORIZED", "登录已失效", HttpStatus.UNAUTHORIZED);
        }
        return user;
    }

    private void checkPasswordBytes(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new BusinessException("VALIDATION_ERROR", "密码不能超过72个UTF-8字节", HttpStatus.BAD_REQUEST);
        }
    }
}
