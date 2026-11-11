package com.rentjcar.blogai.account;

import jakarta.persistence.*;

import java.time.LocalDate;

@Entity
@Table(name = "daily_usage", uniqueConstraints = @UniqueConstraint(columnNames = {"userId", "usageDate"}))
public class DailyUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private LocalDate usageDate;

    private int used;

    public DailyUsage() {}
    public DailyUsage(Long userId, LocalDate usageDate) { this.userId = userId; this.usageDate = usageDate; }

    public Long getUserId() { return userId; }
    public LocalDate getUsageDate() { return usageDate; }
    public int getUsed() { return used; }
    public void setUsed(int used) { this.used = used; }
}
