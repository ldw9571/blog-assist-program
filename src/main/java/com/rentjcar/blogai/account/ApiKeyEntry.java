package com.rentjcar.blogai.account;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.time.LocalDateTime;

/** 회원이 등록한 Gemini API 키 1개 (암호화 저장) */
@Embeddable
public class ApiKeyEntry {

    @Column(name = "key_enc", length = 1000, nullable = false)
    private String enc;

    /** 화면 표시용 끝 4자리 */
    @Column(name = "key_tail", length = 8)
    private String tail;

    @Column(name = "added_at")
    private LocalDateTime addedAt;

    public ApiKeyEntry() {}
    public ApiKeyEntry(String enc, String tail, LocalDateTime addedAt) {
        this.enc = enc; this.tail = tail; this.addedAt = addedAt;
    }

    public String getEnc() { return enc; }
    public String getTail() { return tail; }
    public LocalDateTime getAddedAt() { return addedAt; }
}
