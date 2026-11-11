package com.rentjcar.blogai.account;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 회원 API 키 암호화 (AES-GCM)
 *  - 암호화 비밀값: 환경변수 APP_SECRET, 없으면 데이터 폴더의 secret.key 파일을 자동 생성해서 사용
 *  - DB만 유출돼도 키 원문은 보이지 않음
 */
@Component
public class KeyCipher {

    private static final String PREFIX = "v1:";
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public KeyCipher(@Value("${app.secret:}") String secret,
                     @Value("${app.data-dir:.}") String dataDir) throws Exception {
        String s = secret;
        if (s == null || s.isBlank()) s = loadOrCreateSecretFile(Paths.get(dataDir, "secret.key"));
        byte[] k = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        this.key = new SecretKeySpec(k, "AES");
    }

    public String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) return null;
        try {
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] enc = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + enc.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(enc, 0, out, iv.length, enc.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("ENCRYPT_FAILED");
        }
    }

    /** 복호화 실패(비밀값이 바뀐 경우 등)면 null */
    public String decrypt(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) return null;
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, all, 0, 12));
            return new String(c.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static String loadOrCreateSecretFile(Path f) throws Exception {
        if (Files.exists(f)) return Files.readString(f).trim();
        Files.createDirectories(f.toAbsolutePath().getParent());
        byte[] b = new byte[32];
        new SecureRandom().nextBytes(b);
        String s = Base64.getEncoder().encodeToString(b);
        Files.writeString(f, s);
        try { Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-------")); }
        catch (UnsupportedOperationException ignored) {}   // 윈도우
        return s;
    }
}
