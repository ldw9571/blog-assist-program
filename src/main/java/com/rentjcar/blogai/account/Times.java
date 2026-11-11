package com.rentjcar.blogai.account;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** 서버가 해외에 있어도 날짜는 한국 시간 기준 */
public final class Times {
    public static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private Times() {}
    public static LocalDate today() { return LocalDate.now(KST); }
    public static LocalDateTime now() { return LocalDateTime.now(KST); }
}
