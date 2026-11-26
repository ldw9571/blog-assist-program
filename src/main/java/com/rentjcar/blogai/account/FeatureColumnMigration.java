package com.rentjcar.blogai.account;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 예전 DB는 회원 기능 칸(app_user_feature.feature)이 'CAR, ESTATE, SEO 만 허용'하는 ENUM 형식으로 만들어져 있음.
 * 그대로 두면 새 기능(예: IMAGE)을 회원에게 줄 때 저장 오류가 나서,
 * 서버가 켜질 때 한 번 일반 글자 칸(VARCHAR)으로 바꿔줌. 이미 바뀐 DB에서는 아무 일도 안 함.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)   // 관리자 계정 생성(AdminBootstrap)보다 먼저
public class FeatureColumnMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(FeatureColumnMigration.class);
    private final JdbcTemplate jdbc;

    public FeatureColumnMigration(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void run(ApplicationArguments args) {
        try {
            List<String> types = jdbc.queryForList(
                    "SELECT DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS " +
                    "WHERE TABLE_NAME = 'APP_USER_FEATURE' AND COLUMN_NAME = 'FEATURE'", String.class);
            if (!types.isEmpty() && "ENUM".equalsIgnoreCase(types.get(0))) {
                jdbc.execute("ALTER TABLE APP_USER_FEATURE ALTER COLUMN FEATURE VARCHAR(20)");
                log.info("회원 기능 칸을 VARCHAR(20)으로 변경 완료 (새 기능 추가 대비)");
            }
        } catch (Exception e) {
            log.warn("회원 기능 칸 변경 확인 실패 (계속 진행): {}", e.getMessage());
        }
    }
}
