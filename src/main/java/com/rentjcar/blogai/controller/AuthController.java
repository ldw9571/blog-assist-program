package com.rentjcar.blogai.controller;

import com.rentjcar.blogai.account.*;
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

    public AuthController(AppUserRepository users, PasswordEncoder encoder, CurrentUserService currentUser) {
        this.users = users;
        this.encoder = encoder;
        this.currentUser = currentUser;
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
    public String account() { return "account"; }

    @PostMapping("/account/password")
    public String changePassword(@RequestParam String current,
                                 @RequestParam String password,
                                 @RequestParam String password2,
                                 Model model) {
        AppUser u = currentUser.require();
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
