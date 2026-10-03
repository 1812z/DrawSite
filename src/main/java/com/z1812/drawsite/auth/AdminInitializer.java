package com.z1812.drawsite.auth;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.util.Base64;

@Component
@ConditionalOnProperty(name = "drawsite.bootstrap-admin.enabled", havingValue = "true", matchIfMissing = true)
public class AdminInitializer implements ApplicationRunner {
    private final UserMapper users;
    private final TransactionTemplate transactions;

    public AdminInitializer(UserMapper users, TransactionTemplate transactions) {
        this.users = users;
        this.transactions = transactions;
    }

    @Override
    public void run(ApplicationArguments args) {
        var admin = users.findByUsername("admin");
        if (admin == null || !"BOOTSTRAP_PENDING".equals(admin.getPasswordHash())) return;

        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        String password = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String hash = new BCryptPasswordEncoder().encode(password);
        Integer updated = transactions.execute(status -> users.initializeAdmin(hash));
        // 首次提交成功后输出密码
        if (Integer.valueOf(1).equals(updated)) {
            System.out.printf("%n[DrawSite] 初始管理员账号：admin%n[DrawSite] 初始随机密码：%s%n"
                    + "[DrawSite] 请保存此密码，后续启动不再显示。%n%n", password);
        }
    }
}
