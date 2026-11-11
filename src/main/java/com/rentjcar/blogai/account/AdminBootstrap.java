package com.rentjcar.blogai.account;

import com.rentjcar.blogai.service.EstateStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.EnumSet;

/** 처음 실행 시 관리자 계정 생성 + 예전(회원 기능 전) 분양 자료를 관리자 폴더로 옮김 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final EstateStorageService estateStorage;
    private final String adminUsername;
    private final String adminPassword;

    public AdminBootstrap(AppUserRepository users, PasswordEncoder encoder, EstateStorageService estateStorage,
                          @Value("${app.admin.username:admin}") String adminUsername,
                          @Value("${app.admin.password:}") String adminPassword) {
        this.users = users;
        this.encoder = encoder;
        this.estateStorage = estateStorage;
        this.adminUsername = adminUsername;
        this.adminPassword = adminPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!users.existsByAdminTrue()) {
            String pw = adminPassword;
            boolean generated = false;
            if (pw == null || pw.isBlank()) { pw = randomPassword(); generated = true; }

            AppUser admin = users.findByUsername(adminUsername).orElseGet(AppUser::new);
            admin.setUsername(adminUsername);
            admin.setPasswordHash(encoder.encode(pw));
            admin.setDisplayName("관리자");
            admin.setAdmin(true);
            admin.setStatus(UserStatus.ACTIVE);
            admin.setFeatures(EnumSet.allOf(Feature.class));
            admin.setDailyLimit(250);
            users.save(admin);

            log.warn("==================================================");
            log.warn(" 관리자 계정을 만들었어요. 아이디: {}", adminUsername);
            if (generated) log.warn(" 임시 비밀번호: {}  (로그인 후 관리자 화면에서 바꿔주세요)", pw);
            log.warn("==================================================");
        }
        users.findFirstByAdminTrueOrderByIdAsc().ifPresent(a -> {
            int moved = estateStorage.migrateLegacyTo(a.getId());
            if (moved > 0) log.warn("예전 분양 현장 {}개를 관리자({}) 자료로 옮겼어요.", moved, a.getUsername());
        });
    }

    private static String randomPassword() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i++) sb.append(chars.charAt(r.nextInt(chars.length())));
        return sb.toString();
    }
}
