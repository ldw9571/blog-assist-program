package com.rentjcar.blogai.account;

import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** 모든 화면에 로그인한 회원 정보(me)를 넣어줌 → 상단 바, 메뉴 표시 */
@ControllerAdvice
public class GlobalModelAdvice {

    private final CurrentUserService currentUser;

    public GlobalModelAdvice(CurrentUserService currentUser) { this.currentUser = currentUser; }

    @ModelAttribute
    public void addMe(Model model) {
        AppUser u = currentUser.get();
        if (u != null) model.addAttribute("me", MeView.of(u));
    }
}
