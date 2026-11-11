package com.rentjcar.blogai.account;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/** 지금 로그인한 회원을 DB에서 읽어옴 (관리자가 권한을 바꾸면 바로 반영되도록 매번 조회) */
@Service
public class CurrentUserService {

    private final AppUserRepository users;

    public CurrentUserService(AppUserRepository users) { this.users = users; }

    public AppUser get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) return null;
        return users.findByUsername(auth.getName()).orElse(null);
    }

    public AppUser require() {
        AppUser u = get();
        if (u == null) throw new IllegalStateException("NOT_LOGGED_IN");
        return u;
    }
}
