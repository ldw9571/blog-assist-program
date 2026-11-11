package com.rentjcar.blogai.account;

public enum UserStatus {
    PENDING("승인 대기"),
    ACTIVE("사용 중"),
    SUSPENDED("정지");

    private final String label;
    UserStatus(String label) { this.label = label; }
    public String getLabel() { return label; }
}
