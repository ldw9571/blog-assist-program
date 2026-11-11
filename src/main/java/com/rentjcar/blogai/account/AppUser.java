package com.rentjcar.blogai.account;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 40)
    private String username;

    @Column(nullable = false)
    private String passwordHash;

    /** 이름 또는 상호 */
    @Column(length = 60)
    private String displayName;

    /** 크몽 닉네임 / 연락처 등 관리자 확인용 */
    @Column(length = 120)
    private String contact;

    /** 관리자 메모 (입금 내역 등) */
    @Column(length = 500)
    private String adminMemo;

    private boolean admin;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserStatus status = UserStatus.PENDING;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "app_user_feature", joinColumns = @JoinColumn(name = "user_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "feature", length = 20)
    private Set<Feature> features = new HashSet<>();

    /** 이 날짜까지 사용 가능 (이 날 포함). null 이면 기간 제한 없음 */
    private LocalDate expiresAt;

    private int dailyLimit;

    /** 회원이 직접 등록한 Gemini API 키 (암호화 저장) */
    @Column(length = 1000)
    private String geminiKeyEnc;

    /** 화면 표시용 키 끝 4자리 */
    @Column(length = 8)
    private String geminiKeyTail;

    private LocalDateTime geminiKeyUpdatedAt;

    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime lastLoginAt;
    @Column(length = 64)
    private String lastLoginIp;

    // ── 판단 헬퍼 ──
    public boolean isExpired(LocalDate today) {
        return !admin && expiresAt != null && today.isAfter(expiresAt);
    }

    public boolean can(Feature f) {
        return admin || features.contains(f);
    }

    public Set<Feature> effectiveFeatures() {
        return admin ? EnumSet.allOf(Feature.class) : features;
    }

    public boolean hasApiKey() { return geminiKeyEnc != null && !geminiKeyEnc.isBlank(); }

    // ── getter / setter ──
    public Long getId() { return id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getContact() { return contact; }
    public void setContact(String contact) { this.contact = contact; }
    public String getAdminMemo() { return adminMemo; }
    public void setAdminMemo(String adminMemo) { this.adminMemo = adminMemo; }
    public boolean isAdmin() { return admin; }
    public void setAdmin(boolean admin) { this.admin = admin; }
    public UserStatus getStatus() { return status; }
    public void setStatus(UserStatus status) { this.status = status; }
    public Set<Feature> getFeatures() { return features; }
    public void setFeatures(Set<Feature> features) { this.features = features; }
    public LocalDate getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDate expiresAt) { this.expiresAt = expiresAt; }
    public int getDailyLimit() { return dailyLimit; }
    public void setDailyLimit(int dailyLimit) { this.dailyLimit = dailyLimit; }
    public String getGeminiKeyEnc() { return geminiKeyEnc; }
    public void setGeminiKeyEnc(String geminiKeyEnc) { this.geminiKeyEnc = geminiKeyEnc; }
    public String getGeminiKeyTail() { return geminiKeyTail; }
    public void setGeminiKeyTail(String geminiKeyTail) { this.geminiKeyTail = geminiKeyTail; }
    public LocalDateTime getGeminiKeyUpdatedAt() { return geminiKeyUpdatedAt; }
    public void setGeminiKeyUpdatedAt(LocalDateTime t) { this.geminiKeyUpdatedAt = t; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getLastLoginAt() { return lastLoginAt; }
    public void setLastLoginAt(LocalDateTime lastLoginAt) { this.lastLoginAt = lastLoginAt; }
    public String getLastLoginIp() { return lastLoginIp; }
    public void setLastLoginIp(String lastLoginIp) { this.lastLoginIp = lastLoginIp; }
}
