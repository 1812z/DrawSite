package com.z1812.drawsite.auth;

import cn.dev33.satoken.stp.StpUtil;
import com.z1812.drawsite.common.api.PageResponse;
import com.z1812.drawsite.common.exception.BusinessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Locale;
import static com.z1812.drawsite.auth.UserDtos.*;

@Service
public class UserService {
    private final UserMapper users;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public UserService(UserMapper users) { this.users = users; }

    @Transactional
    public AuthDtos.UserView updateProfile(ProfileRequest request) {
        var user = lockUser(StpUtil.getLoginIdAsString());
        applyProfile(user, request.username(), request.displayName());
        save(user);
        return AuthDtos.UserView.from(user);
    }

    @Transactional
    public void changePassword(PasswordRequest request) {
        var user = lockUser(StpUtil.getLoginIdAsString());
        checkPassword(request.oldPassword());
        checkPassword(request.newPassword());
        if (!encoder.matches(request.oldPassword(), user.getPasswordHash())) {
            throw new BusinessException("OLD_PASSWORD_INCORRECT", "原密码不正确", HttpStatus.BAD_REQUEST);
        }
        user.setPasswordHash(encoder.encode(request.newPassword()));
        save(user);
        invalidateAfterCommit(user.getId());
    }

    public PageResponse<AdminUserView> list(int page, int size) {
        StpUtil.checkRole("ADMIN");
        return new PageResponse<>(users.listUsers(size, (long) (page - 1) * size).stream()
                .map(AdminUserView::from).toList(), users.countUsers(), page, size);
    }

    public AdminUserView detail(String id) {
        StpUtil.checkRole("ADMIN");
        var user = users.selectOneById(id);
        if (user == null) throw notFound();
        return AdminUserView.from(user);
    }

    @Transactional
    public AdminUserView update(String id, AdminUpdateRequest request) {
        StpUtil.checkRole("ADMIN");
        var user = lockUser(id);
        boolean invalidate = !user.getRole().equals(request.role()) || !user.getEnabled().equals(request.enabled());
        applyProfile(user, request.username(), request.displayName());
        user.setRole(request.role());
        user.setEnabled(request.enabled());
        save(user);
        if (invalidate) invalidateAfterCommit(id);
        return AdminUserView.from(user);
    }

    @Transactional
    public void resetPassword(String id, ResetPasswordRequest request) {
        StpUtil.checkRole("ADMIN");
        var user = lockUser(id);
        checkPassword(request.newPassword());
        user.setPasswordHash(encoder.encode(request.newPassword()));
        save(user);
        invalidateAfterCommit(id);
    }

    private UserEntity lockUser(String id) {
        var user = users.lockUser(id);
        if (user == null) throw notFound();
        return user;
    }

    private void applyProfile(UserEntity user, String username, String displayName) {
        String name = displayName.strip();
        if (name.isEmpty()) throw new BusinessException("VALIDATION_ERROR", "显示名称不能为空", HttpStatus.BAD_REQUEST);
        user.setUsername(username.toLowerCase(Locale.ROOT));
        user.setDisplayName(name);
    }

    private void save(UserEntity user) {
        user.setUpdatedAt(OffsetDateTime.now());
        try { users.update(user); }
        catch (DuplicateKeyException exception) {
            throw new BusinessException("USERNAME_EXISTS", "用户名已存在", HttpStatus.CONFLICT);
        }
    }

    private void checkPassword(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new BusinessException("VALIDATION_ERROR", "密码不能超过72个UTF-8字节", HttpStatus.BAD_REQUEST);
        }
    }

    private void invalidateAfterCommit(String id) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() { StpUtil.logout(id); }
        });
    }

    private BusinessException notFound() {
        return new BusinessException("USER_NOT_FOUND", "用户不存在", HttpStatus.NOT_FOUND);
    }
}
