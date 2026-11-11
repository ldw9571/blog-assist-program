package com.rentjcar.blogai.account;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** 화면 상단 표시용 회원 정보 */
public record MeView(String username, String displayName, boolean admin,
                     boolean car, boolean seo, boolean estate,
                     LocalDate expiresAt, long daysLeft) {

    public static MeView of(AppUser u) {
        LocalDate today = Times.today();
        long left = u.getExpiresAt() == null ? -1 : ChronoUnit.DAYS.between(today, u.getExpiresAt());
        String name = (u.getDisplayName() == null || u.getDisplayName().isBlank()) ? u.getUsername() : u.getDisplayName();
        return new MeView(u.getUsername(), name, u.isAdmin(),
                u.can(Feature.CAR), u.can(Feature.SEO), u.can(Feature.ESTATE),
                u.getExpiresAt(), left);
    }

    public boolean hasExpiry() { return expiresAt != null; }
}
