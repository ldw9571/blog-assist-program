package com.rentjcar.blogai.account;

import jakarta.persistence.*;

/** 간단한 설정값 저장 (예: IP 제한 켜짐 여부) */
@Entity
@Table(name = "app_setting")
public class AppSetting {
    @Id
    @Column(length = 60)
    private String name;

    @Column(length = 500)
    private String val;

    public AppSetting() {}
    public AppSetting(String name, String val) { this.name = name; this.val = val; }
    public String getName() { return name; }
    public String getVal() { return val; }
    public void setVal(String val) { this.val = val; }
}
