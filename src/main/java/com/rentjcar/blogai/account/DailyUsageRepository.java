package com.rentjcar.blogai.account;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface DailyUsageRepository extends JpaRepository<DailyUsage, Long> {
    Optional<DailyUsage> findByUserIdAndUsageDate(Long userId, LocalDate usageDate);
    List<DailyUsage> findByUsageDate(LocalDate usageDate);
    void deleteByUserId(Long userId);
}
