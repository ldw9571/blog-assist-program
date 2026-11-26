package com.rentjcar.blogai.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 🖼️ 사진 변환 도구
 *  - 블로그 중복 이미지 방지용: 자르기 · 살짝 회전 · 밝기/대비/색감 · 크기 줄이기 · 촬영정보(EXIF) 삭제
 *  - 변환은 전부 '사용자 브라우저'에서 처리 → 사진이 서버로 올라오지 않음 (서버 메모리·업로드 한도 영향 없음)
 *  - 서버는 화면만 내려주고, 로그인·기능 권한 확인은 AccessGuardInterceptor 가 함
 */
@Controller
public class ImageToolController {

    @GetMapping("/images")
    public String imageTool() {
        return "images";
    }
}
