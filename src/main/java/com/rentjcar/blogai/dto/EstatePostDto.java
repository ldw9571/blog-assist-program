package com.rentjcar.blogai.dto;

import lombok.Getter;
import lombok.Setter;

// 저장된 포스팅 1건
@Getter
@Setter
public class EstatePostDto {
    private String id;
    private String keyword;
    private String angle;
    private String title;
    private String createdAt;
    private String score;      // 노출 점검 점수 (없으면 빈 값)
    private String post;       // 본문 원문 (@@소제목@@ 포함)
    private String guide;
    private String seoResult;

    /** 화면 색상 구분용 숫자 점수 (없으면 -1) */
    public int getScoreNum() {
        try { return Integer.parseInt(score); } catch (Exception e) { return -1; }
    }
}