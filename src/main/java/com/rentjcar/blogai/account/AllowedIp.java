package com.rentjcar.blogai.account;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/** 접속 허용 IP (정확한 IP 또는 211.234.0.0/16 같은 대역) */
@Entity
@Table(name = "allowed_ip")
public class AllowedIp {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String ip;

    @Column(length = 120)
    private String memo;

    private LocalDateTime createdAt = LocalDateTime.now();

    public AllowedIp() {}
    public AllowedIp(String ip, String memo) { this.ip = ip; this.memo = memo; }

    public Long getId() { return id; }
    public String getIp() { return ip; }
    public String getMemo() { return memo; }
    public void setMemo(String memo) { this.memo = memo; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
