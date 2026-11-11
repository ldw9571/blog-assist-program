package com.rentjcar.blogai.service;

import com.rentjcar.blogai.account.*;
import org.springframework.stereotype.Service;

/**
 * 회원별 하루 사용 횟수 관리 (한국 날짜 기준, 자정에 초기화)
 *  - 한도는 관리자가 회원마다 지정
 *  - 컨트롤러는 예전처럼 canUse() / increaseCount() 만 부르면 됨
 */
@Service
public class UsageService {

    private final CurrentUserService currentUser;
    private final DailyUsageRepository usageRepo;

    public UsageService(CurrentUserService currentUser, DailyUsageRepository usageRepo) {
        this.currentUser = currentUser;
        this.usageRepo = usageRepo;
    }

    public int getDailyLimit() {
        AppUser u = currentUser.get();
        return u == null ? 0 : Math.max(0, u.getDailyLimit());
    }

    public int getUsedCount() {
        AppUser u = currentUser.get();
        return u == null ? 0 : usedOf(u.getId());
    }

    public int getRemainingCount() {
        AppUser u = currentUser.get();
        if (u == null) return 0;
        return Math.max(0, u.getDailyLimit() - usedOf(u.getId()));
    }

    public boolean canUse() {
        return getRemainingCount() > 0;
    }

    /** 동시에 여러 요청이 와도 숫자가 꼬이지 않게 한 번에 하나씩 */
    public synchronized void increaseCount() {
        AppUser u = currentUser.get();
        if (u == null) return;
        DailyUsage d = usageRepo.findByUserIdAndUsageDate(u.getId(), Times.today())
                .orElseGet(() -> new DailyUsage(u.getId(), Times.today()));
        d.setUsed(d.getUsed() + 1);
        usageRepo.save(d);
    }

    public int usedOf(Long userId) {
        return usageRepo.findByUserIdAndUsageDate(userId, Times.today()).map(DailyUsage::getUsed).orElse(0);
    }
}
