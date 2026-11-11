package com.rentjcar.blogai.account;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 로그인한 회원이 기능 화면에 들어올 때마다 확인
 *  - 승인 대기 / 정지 / 기간 만료 → 안내 화면(/status)
 *  - 권한 없는 기능 → 쓸 수 있는 첫 기능으로 이동
 *  - Gemini API 키 미등록 → 내 정보(키 등록)로 이동
 */
@Component
public class AccessGuardInterceptor implements HandlerInterceptor {

    private final CurrentUserService currentUser;

    public AccessGuardInterceptor(CurrentUserService currentUser) { this.currentUser = currentUser; }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
        String uri = req.getRequestURI();
        Feature feature = Feature.ofPath(uri);
        if (feature == null) return true;          // 기능 화면이 아니면 통과 (/status, /admin 등)

        AppUser u = currentUser.get();
        if (u == null) { res.sendRedirect("/login"); return false; }
        if (u.isAdmin()) return true;

        if (u.getStatus() != UserStatus.ACTIVE || u.isExpired(Times.today())) {
            res.sendRedirect("/status");
            return false;
        }
        if (!u.can(feature)) {
            Feature first = u.effectiveFeatures().stream().sorted().findFirst().orElse(null);
            res.sendRedirect(first == null ? "/status" : first.getPath());
            return false;
        }
        if (!u.hasApiKey()) {
            res.sendRedirect("/account?needKey");
            return false;
        }
        return true;
    }
}
