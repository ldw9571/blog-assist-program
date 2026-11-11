package com.rentjcar.blogai.controller;

import com.rentjcar.blogai.account.*;
import com.rentjcar.blogai.service.GeminiClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** 로그인 / 가입 신청 / 상태 안내 / 내 비밀번호 변경 */
@Controller
public class AuthController {

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
                         Model model) {
        String id = username.trim().toLowerCase();
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
    // Gemini API 키 등록 / 확인 / 삭제
    // ─────────────────────────────────────────
    @PostMapping("/account/apikey")
    public String saveApiKey(@RequestParam String apiKey, Model model) {
        AppUser u = currentUser.require();
        String key = apiKey.trim();
        if (!key.matches("[A-Za-z0-9._\\-]{20,200}")) {
            model.addAttribute("keyError", "API 키 형식이 올바르지 않아요. 구글 AI 스튜디오에서 복사한 키를 그대로 붙여넣어 주세요.");
            addKeyInfo(model, u);
            return "account";
        }
        String test = gemini.testKey(key);
        if ("INVALID_KEY".equals(test) || "FORBIDDEN".equals(test)) {
            model.addAttribute("keyError", "❌ 구글에서 사용할 수 없는 키라고 응답했어요. 키를 다시 확인해 주세요. (저장하지 않았어요)");
            addKeyInfo(model, u);
            return "account";
        }
        u.setGeminiKeyEnc(cipher.encrypt(key));
        u.setGeminiKeyTail(key.substring(key.length() - 4));
        u.setGeminiKeyUpdatedAt(Times.now());
        users.save(u);
        if ("OK".equals(test)) {
            model.addAttribute("keyInfo", "✅ API 키를 등록했어요. 연결도 정상이에요! 이제 블로그 도우미를 사용할 수 있어요.");
        } else {
            model.addAttribute("keyInfo", "💾 API 키를 저장했어요. 다만 지금 연결 확인은 되지 않았어요 (" + testMessage(test)
                    + "). 잠시 후 '연결 확인'을 눌러보세요.");
        }
        addKeyInfo(model, u);
        return "account";
    }

    @PostMapping("/account/apikey/test")
    public String testApiKey(Model model) {
        AppUser u = currentUser.require();
        String key = u.hasApiKey() ? cipher.decrypt(u.getGeminiKeyEnc()) : null;
        if (key == null) {
            model.addAttribute("keyError", "등록된 API 키가 없어요.");
        } else {
            String test = gemini.testKey(key);
            if ("OK".equals(test)) model.addAttribute("keyInfo", "✅ 연결 정상이에요.");
            else model.addAttribute("keyError", "❌ " + testMessage(test));
        }
        addKeyInfo(model, u);
        return "account";
    }

    @PostMapping("/account/apikey/delete")
    public String deleteApiKey(Model model) {
        AppUser u = currentUser.require();
        u.setGeminiKeyEnc(null);
        u.setGeminiKeyTail(null);
        u.setGeminiKeyUpdatedAt(null);
        users.save(u);
        model.addAttribute("keyInfo", "🗑️ API 키를 삭제했어요.");
        addKeyInfo(model, u);
        return "account";
    }

    private void addKeyInfo(Model model, AppUser u) {
        model.addAttribute("hasKey", u.hasApiKey());
        model.addAttribute("keyTail", u.getGeminiKeyTail());
        model.addAttribute("keyUpdatedAt", u.getGeminiKeyUpdatedAt());
    }

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
