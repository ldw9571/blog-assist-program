package com.rentjcar.blogai.controller;

import com.rentjcar.blogai.account.*;
import com.rentjcar.blogai.service.UsageService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.util.*;

/**
 * 관리자 화면 (/admin)
 *  - 가입 승인 / 정지
 *  - 회원별 사용 기능, 이용 만료일, 하루 사용 한도
 *  - 접속 허용 IP 관리 (+ 최근 차단된 IP 바로 허용)
 */
@Controller
@RequestMapping("/admin")
public class AdminController {

    private final AppUserRepository users;
    private final DailyUsageRepository usageRepo;
    private final UsageService usageService;
    private final IpAccessService ipAccess;
    private final PasswordEncoder encoder;
    private final CurrentUserService currentUser;
    private final int defaultDailyLimit;

    public AdminController(AppUserRepository users, DailyUsageRepository usageRepo, UsageService usageService,
                           IpAccessService ipAccess, PasswordEncoder encoder, CurrentUserService currentUser,
                           @Value("${app.default-daily-limit:30}") int defaultDailyLimit) {
        this.users = users;
        this.usageRepo = usageRepo;
        this.usageService = usageService;
        this.ipAccess = ipAccess;
        this.encoder = encoder;
        this.currentUser = currentUser;
        this.defaultDailyLimit = defaultDailyLimit;
    }

    public record UserRow(AppUser user, int usedToday, long daysLeft, boolean expired) {}

    @GetMapping
    public String dashboard(@RequestParam(value = "edit", required = false) Long editId,
                            HttpServletRequest req, Model model) {
        LocalDate today = Times.today();
        List<UserRow> rows = new ArrayList<>();
        for (AppUser u : users.findAllByOrderByIdDesc()) {
            long left = u.getExpiresAt() == null ? Long.MAX_VALUE
                    : java.time.temporal.ChronoUnit.DAYS.between(today, u.getExpiresAt());
            rows.add(new UserRow(u, usageService.usedOf(u.getId()), left, u.isExpired(today)));
        }
        model.addAttribute("rows", rows);
        model.addAttribute("pendingCount", users.countByStatus(UserStatus.PENDING));
        model.addAttribute("features", Feature.values());
        model.addAttribute("statuses", UserStatus.values());
        model.addAttribute("editId", editId);
        model.addAttribute("today", today);
        model.addAttribute("defaultDailyLimit", defaultDailyLimit);

        model.addAttribute("ipEnabled", ipAccess.isEnabled());
        model.addAttribute("ipForceOff", ipAccess.isForceOff());
        model.addAttribute("ipList", ipAccess.list());
        model.addAttribute("blockedList", ipAccess.recentBlocked());
        model.addAttribute("myIp", req.getRemoteAddr());
        return "admin";
    }

    // ─────────────────────────────────────────
    // 회원 관리
    // ─────────────────────────────────────────
    /** 승인: 기능 전체 + 기본 한도 + 오늘부터 1개월 */
    @PostMapping("/users/{id}/approve")
    public String approve(@PathVariable Long id, RedirectAttributes ra) {
        AppUser u = find(id);
        u.setStatus(UserStatus.ACTIVE);
        if (u.getFeatures().isEmpty()) u.setFeatures(EnumSet.allOf(Feature.class));
        if (u.getDailyLimit() <= 0) u.setDailyLimit(defaultDailyLimit);
        if (u.getExpiresAt() == null || u.isExpired(Times.today())) u.setExpiresAt(Times.today().plusMonths(1).minusDays(1));
        users.save(u);
        ra.addFlashAttribute("infoMsg", "✅ " + u.getUsername() + " 님을 승인했어요. 기능·기간을 확인해 주세요.");
        return "redirect:/admin?edit=" + id + "#user-" + id;
    }

    @PostMapping("/users/{id}")
    public String update(@PathVariable Long id,
                         @RequestParam UserStatus status,
                         @RequestParam(value = "features", required = false) List<Feature> features,
                         @RequestParam(defaultValue = "0") int dailyLimit,
                         @RequestParam(defaultValue = "") String expiresAt,
                         @RequestParam(defaultValue = "") String displayName,
                         @RequestParam(defaultValue = "") String contact,
                         @RequestParam(defaultValue = "") String adminMemo,
                         RedirectAttributes ra) {
        AppUser u = find(id);
        if (u.isAdmin() && !u.getId().equals(currentUser.require().getId())) {
            ra.addFlashAttribute("errorMsg", "다른 관리자 계정은 바꿀 수 없어요.");
            return "redirect:/admin";
        }
        if (!u.isAdmin()) {
            u.setStatus(status);
            u.setFeatures(features == null ? new HashSet<>() : EnumSet.copyOf(features));
            if (expiresAt.isBlank()) u.setExpiresAt(null);
            else {
                try { u.setExpiresAt(LocalDate.parse(expiresAt)); }
                catch (Exception e) { ra.addFlashAttribute("errorMsg", "만료일 형식이 올바르지 않아요."); return "redirect:/admin?edit=" + id; }
            }
        }
        u.setDailyLimit(Math.max(0, Math.min(dailyLimit, 10000)));
        u.setDisplayName(cut(displayName.trim(), 60));
        u.setContact(cut(contact.trim(), 120));
        u.setAdminMemo(cut(adminMemo.trim(), 500));
        users.save(u);
        ra.addFlashAttribute("infoMsg", "💾 " + u.getUsername() + " 님 정보를 저장했어요.");
        return "redirect:/admin#user-" + id;
    }

    /** 기간 연장: 남은 기간이 있으면 그 뒤로, 이미 끝났으면 오늘부터 */
    @PostMapping("/users/{id}/extend")
    public String extend(@PathVariable Long id, @RequestParam(defaultValue = "1") int months, RedirectAttributes ra) {
        AppUser u = find(id);
        if (u.isAdmin()) return "redirect:/admin";
        months = Math.max(1, Math.min(months, 24));
        LocalDate today = Times.today();
        LocalDate base = (u.getExpiresAt() == null || u.getExpiresAt().isBefore(today)) ? today.minusDays(1) : u.getExpiresAt();
        u.setExpiresAt(base.plusMonths(months));
        if (u.getStatus() == UserStatus.PENDING) u.setStatus(UserStatus.ACTIVE);
        if (u.getDailyLimit() <= 0) u.setDailyLimit(defaultDailyLimit);
        if (u.getFeatures().isEmpty()) u.setFeatures(EnumSet.allOf(Feature.class));
        users.save(u);
        ra.addFlashAttribute("infoMsg", "📅 " + u.getUsername() + " 님 이용기간을 " + u.getExpiresAt() + " 까지로 연장했어요.");
        return "redirect:/admin#user-" + id;
    }

    @PostMapping("/users/{id}/password")
    public String resetPassword(@PathVariable Long id, @RequestParam String password, RedirectAttributes ra) {
        AppUser u = find(id);
        if (password.length() < 8) {
            ra.addFlashAttribute("errorMsg", "비밀번호는 8자 이상으로 정해주세요.");
        } else {
            u.setPasswordHash(encoder.encode(password));
            users.save(u);
            ra.addFlashAttribute("infoMsg", "🔑 " + u.getUsername() + " 님 비밀번호를 바꿨어요. 회원에게 알려주세요.");
        }
        return "redirect:/admin?edit=" + id + "#user-" + id;
    }

    @Transactional
    @PostMapping("/users/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        AppUser u = find(id);
        if (u.isAdmin()) {
            ra.addFlashAttribute("errorMsg", "관리자 계정은 지울 수 없어요.");
            return "redirect:/admin";
        }
        usageRepo.deleteByUserId(u.getId());
        users.delete(u);
        ra.addFlashAttribute("infoMsg", "🗑️ " + u.getUsername() + " 님 계정을 삭제했어요. (작성 자료 폴더는 서버에 남아 있어요)");
        return "redirect:/admin";
    }

    // ─────────────────────────────────────────
    // 접속 허용 IP
    // ─────────────────────────────────────────
    @PostMapping("/ip/toggle")
    public String toggleIp(@RequestParam boolean enabled, HttpServletRequest req, RedirectAttributes ra) {
        if (enabled) {
            // 켜는 순간 관리자 본인이 막히지 않도록 지금 IP를 자동으로 추가
            String my = req.getRemoteAddr();
            try { ipAccess.add(my, "관리자 (자동 추가)"); } catch (IllegalArgumentException ignored) {}
            ipAccess.setEnabled(true);
            ra.addFlashAttribute("infoMsg", "🔒 IP 제한을 켰어요. 지금 관리자 IP(" + my + ")는 자동으로 허용했어요.");
        } else {
            ipAccess.setEnabled(false);
            ra.addFlashAttribute("infoMsg", "🔓 IP 제한을 껐어요. 로그인만 하면 어디서든 접속돼요.");
        }
        return "redirect:/admin#ip";
    }

    @PostMapping("/ip/add")
    public String addIp(@RequestParam String ip, @RequestParam(defaultValue = "") String memo, RedirectAttributes ra) {
        try {
            ipAccess.add(ip, cut(memo, 120));
            ra.addFlashAttribute("infoMsg", "✅ " + ip.trim() + " 를 허용했어요.");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMsg", "IP 형식이 올바르지 않아요. 예) 211.234.10.5 또는 211.234.0.0/16");
        }
        return "redirect:/admin#ip";
    }

    @PostMapping("/ip/{id}/delete")
    public String deleteIp(@PathVariable Long id, RedirectAttributes ra) {
        ipAccess.remove(id);
        ra.addFlashAttribute("infoMsg", "🗑️ 허용 IP를 지웠어요.");
        return "redirect:/admin#ip";
    }

    private AppUser find(Long id) {
        return users.findById(id).orElseThrow(() -> new IllegalArgumentException("USER_NOT_FOUND"));
    }

    private static String cut(String s, int max) { return s.length() > max ? s.substring(0, max) : s; }
}
