package com.rentjcar.blogai.controller;

import com.rentjcar.blogai.account.*;
import com.rentjcar.blogai.service.GeminiClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 로그인 / 가입 신청 / 상태 안내 / 내 비밀번호 변경 */
@Controller
public class AuthController {

    /** Gemini API 키 형식 (영문·숫자·._- 20~200자) */
    private static final String KEY_PATTERN = "[A-Za-z0-9._\\-]{20,200}";

    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final CurrentUserService currentUser;
    private final KeyCipher cipher;
    private final GeminiClient gemini;

    public AuthController(AppUserRepository users, PasswordEncoder encoder, CurrentUserService currentUser,
                          KeyCipher cipher, GeminiClient gemini) {
        this.users = users;
        this.encoder = encoder;
        this.currentUser = currentUser;
        this.cipher = cipher;
        this.gemini = gemini;
    }

    @GetMapping("/login")
    public String login() {
        return currentUser.get() != null ? "redirect:/" : "login";
    }

    @GetMapping("/signup")
    public String signupForm() { return "signup"; }

    @PostMapping("/signup")
    public String signup(@RequestParam String username,
                         @RequestParam String password,
                         @RequestParam String password2,
                         @RequestParam(defaultValue = "") String displayName,
                         @RequestParam(defaultValue = "") String contact,
                         @RequestParam(defaultValue = "") String apiKey,
                         @RequestParam(defaultValue = "") String apiKey2,
                         @RequestParam(defaultValue = "") String apiKey3,
                         Model model) {
        String id = username.trim().toLowerCase();
        // 1번 키는 필수, 2·3번은 선택 (빈 칸은 무시, 같은 키 중복 제거)
        List<String> keys = new ArrayList<>();
        for (String k : List.of(apiKey, apiKey2, apiKey3)) {
            String t = k.trim();
            if (!t.isEmpty() && !keys.contains(t)) keys.add(t);
        }
        model.addAttribute("username", id);
        model.addAttribute("displayName", displayName);
        model.addAttribute("contact", contact);

        String err = null;
        if (!id.matches("[a-z0-9_]{4,20}"))           err = "아이디는 영문 소문자·숫자·_ 로 4~20자로 만들어주세요.";
        else if (users.existsByUsername(id))          err = "이미 사용 중인 아이디예요.";
        else if (password.length() < 8 || password.length() > 64) err = "비밀번호는 8자 이상으로 만들어주세요.";
        else if (!password.equals(password2))          err = "비밀번호 확인이 일치하지 않아요.";
        else if (displayName.isBlank())                err = "이름(또는 상호)을 입력해주세요.";
        else if (contact.isBlank())                    err = "크몽 닉네임을 입력해주세요. (입금·결제 확인용)";
        else if (keys.isEmpty())                       err = "Gemini API 키를 1개 이상 입력해주세요.";
        else {
            for (int i = 0; i < keys.size() && err == null; i++) {
                if (!keys.get(i).matches(KEY_PATTERN))
                    err = (i + 1) + "번째 API 키 형식이 올바르지 않아요. 구글 AI 스튜디오에서 복사한 키를 그대로 붙여넣어 주세요.";
            }
        }
        if (err == null) {
            for (int i = 0; i < keys.size() && err == null; i++) {
                String test = gemini.testKey(keys.get(i));   // 구글에 실제로 되는 키인지 확인
                if ("INVALID_KEY".equals(test) || "FORBIDDEN".equals(test))
                    err = (i + 1) + "번째 API 키는 구글에서 사용할 수 없는 키라고 응답했어요. 키를 다시 확인해주세요.";
            }
        }
        if (err != null) {
            model.addAttribute("errorMsg", err);
            return "signup";
        }

        AppUser u = new AppUser();
        u.setUsername(id);
        u.setPasswordHash(encoder.encode(password));
        u.setDisplayName(cut(displayName.trim(), 60));
        u.setContact(cut(contact.trim(), 120));
        u.setStatus(UserStatus.PENDING);
        for (String k : keys) u.getApiKeys().add(newEntry(k));
        users.save(u);
        return "redirect:/login?signedUp";
    }

    /** 승인 대기 / 정지 / 만료 / 권한 없음 안내 */
    @GetMapping("/status")
    public String status(Model model) {
        AppUser u = currentUser.require();
        String state;
        if (u.isAdmin()) return "redirect:/";
        if (u.getStatus() == UserStatus.PENDING)        state = "pending";
        else if (u.getStatus() == UserStatus.SUSPENDED) state = "suspended";
        else if (u.isExpired(Times.today()))            state = "expired";
        else if (u.getFeatures().isEmpty())             state = "nofeature";
        else return "redirect:/";
        model.addAttribute("state", state);
        model.addAttribute("user", u);
        return "status";
    }

    @GetMapping("/account")
    public String account(Model model) {
        addKeyInfo(model, currentUser.require());
        return "account";
    }

    // ─────────────────────────────────────────
    // Gemini API 키 (최대 3개) 추가 / 확인 / 순서 / 삭제
    //  1번 키부터 쓰고, 한도 초과·막힘이면 다음 키로 자동 전환
    // ─────────────────────────────────────────
    @PostMapping("/account/apikey")
    public String addApiKey(@RequestParam String apiKey, Model model) {
        AppUser u = currentUser.require();
        String key = apiKey.trim();
        String err = null;
        if (u.getApiKeyCount() >= AppUser.MAX_API_KEYS)
            err = "API 키는 최대 " + AppUser.MAX_API_KEYS + "개까지 등록할 수 있어요. 기존 키를 지우고 추가해 주세요.";
        else if (!key.matches(KEY_PATTERN))
            err = "API 키 형식이 올바르지 않아요. 구글 AI 스튜디오에서 복사한 키를 그대로 붙여넣어 주세요.";
        else if (decryptAll(u).contains(key))
            err = "이미 등록된 키예요.";
        if (err != null) return keyPage(model, u, err, null);

        String test = gemini.testKey(key);
        if ("INVALID_KEY".equals(test) || "FORBIDDEN".equals(test))
            return keyPage(model, u, "❌ 구글에서 사용할 수 없는 키라고 응답했어요. 키를 다시 확인해 주세요. (저장하지 않았어요)", null);

        u.getApiKeys().add(newEntry(key));
        users.save(u);
        String no = u.getApiKeyCount() + "번 키";
        if ("OK".equals(test))
            return keyPage(model, u, null, "✅ " + no + "로 등록했어요. 연결도 정상이에요!");
        return keyPage(model, u, null, "💾 " + no + "로 저장했어요. 다만 지금 연결 확인은 되지 않았어요 ("
                + testMessage(test) + "). 잠시 후 '확인'을 눌러보세요.");
    }

    @PostMapping("/account/apikey/{idx}/test")
    public String testApiKey(@PathVariable int idx, Model model) {
        AppUser u = currentUser.require();
        if (!validIdx(u, idx)) return keyPage(model, u, "해당 키가 없어요.", null);
        String key = cipher.decrypt(u.getApiKeys().get(idx).getEnc());
        if (key == null) return keyPage(model, u, "저장된 키를 읽지 못했어요. 지우고 다시 등록해 주세요.", null);
        String test = gemini.testKey(key);
        return "OK".equals(test)
                ? keyPage(model, u, null, "✅ " + (idx + 1) + "번 키 연결 정상이에요.")
                : keyPage(model, u, "❌ " + (idx + 1) + "번 키: " + testMessage(test), null);
    }

    /** 한 칸 위로 (먼저 쓰이게) */
    @PostMapping("/account/apikey/{idx}/up")
    public String moveUpApiKey(@PathVariable int idx, Model model) {
        AppUser u = currentUser.require();
        if (validIdx(u, idx) && idx > 0) {
            Collections.swap(u.getApiKeys(), idx, idx - 1);
            users.save(u);
            return keyPage(model, u, null, "↕️ 순서를 바꿨어요.");
        }
        return keyPage(model, u, null, null);
    }

    @PostMapping("/account/apikey/{idx}/delete")
    public String deleteApiKey(@PathVariable int idx, Model model) {
        AppUser u = currentUser.require();
        if (!validIdx(u, idx)) return keyPage(model, u, "해당 키가 없어요.", null);
        u.getApiKeys().remove(idx);
        users.save(u);
        return keyPage(model, u, null, "🗑️ 키를 삭제했어요.");
    }

    private String keyPage(Model model, AppUser u, String err, String info) {
        if (err != null) model.addAttribute("keyError", err);
        if (info != null) model.addAttribute("keyInfo", info);
        addKeyInfo(model, u);
        return "account";
    }

    private void addKeyInfo(Model model, AppUser u) {
        model.addAttribute("hasKey", u.hasApiKey());
        model.addAttribute("apiKeys", u.getApiKeys());
        model.addAttribute("keyCount", u.getApiKeyCount());
        model.addAttribute("maxKeys", AppUser.MAX_API_KEYS);
    }

    private ApiKeyEntry newEntry(String key) {
        return new ApiKeyEntry(cipher.encrypt(key), key.substring(key.length() - 4), Times.now());
    }

    private List<String> decryptAll(AppUser u) {
        List<String> out = new ArrayList<>();
        for (ApiKeyEntry e : u.getApiKeys()) {
            String k = cipher.decrypt(e.getEnc());
            if (k != null) out.add(k);
        }
        return out;
    }

    private static boolean validIdx(AppUser u, int idx) { return idx >= 0 && idx < u.getApiKeyCount(); }

    private static String testMessage(String code) {
        if (code == null) return "알 수 없는 오류";
        return switch (code) {
            case "INVALID_KEY"    -> "올바르지 않은 키예요. 다시 등록해 주세요.";
            case "FORBIDDEN"      -> "구글에서 이 키의 사용을 막았어요. 구글 AI 스튜디오에서 키 상태를 확인해 주세요.";
            case "QUOTA_EXCEEDED" -> "이 키의 사용 한도를 넘었어요. 잠시 후 다시 시도해 주세요.";
            case "BUSY"           -> "구글 서버가 붐벼요. 잠시 후 다시 시도해 주세요.";
            case "NETWORK"        -> "구글 서버에 연결하지 못했어요.";
            default               -> "확인 중 오류 (" + code + ")";
        };
    }

    @PostMapping("/account/password")
    public String changePassword(@RequestParam String current,
                                 @RequestParam String password,
                                 @RequestParam String password2,
                                 Model model) {
        AppUser u = currentUser.require();
        addKeyInfo(model, u);
        if (!encoder.matches(current, u.getPasswordHash())) {
            model.addAttribute("errorMsg", "현재 비밀번호가 맞지 않아요.");
        } else if (password.length() < 8 || password.length() > 64) {
            model.addAttribute("errorMsg", "새 비밀번호는 8자 이상으로 만들어주세요.");
        } else if (!password.equals(password2)) {
            model.addAttribute("errorMsg", "새 비밀번호 확인이 일치하지 않아요.");
        } else {
            u.setPasswordHash(encoder.encode(password));
            users.save(u);
            model.addAttribute("infoMsg", "✅ 비밀번호를 바꿨어요.");
        }
        return "account";
    }

    private static String cut(String s, int max) { return s.length() > max ? s.substring(0, max) : s; }
}
