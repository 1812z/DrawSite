package com.z1812.drawsite.auth;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import java.util.List;

@Configuration
public class AuthConfiguration implements WebMvcConfigurer {
    private final AuthService auth;
    public AuthConfiguration(AuthService auth) { this.auth = auth; }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handler -> {
                    StpUtil.checkLogin();
                    auth.currentUser();
                }))
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/register", "/api/auth/login", "/api/system/health",
                        "/api/platform/public-settings");
    }

    @Bean
    public StpInterface stpInterface(UserMapper users) {
        return new StpInterface() {
            public List<String> getPermissionList(Object loginId, String loginType) { return List.of(); }
            public List<String> getRoleList(Object loginId, String loginType) {
                var user = users.selectOneById(loginId.toString());
                return user != null && Boolean.TRUE.equals(user.getEnabled()) ? List.of(user.getRole()) : List.of();
            }
        };
    }
}
