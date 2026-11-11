package com.rentjcar.blogai.dto;

import lombok.Getter;
import lombok.Setter;

// 분양 블로그: AI가 추천한 키워드 1개 (화면에서 클릭하면 포스팅 생성 폼에 자동 입력)
@Getter
@Setter
public class EstateKeywordDto {
    private String keyword;      // 예: 광주학동오피스텔
    private String level;        // 대표 / 중간 / 롱테일
    private String angle;        // 종합소개, 입지교통, 평면타입 ...
    private String titleIdea;    // 제목안
    private String subKeywords;  // 같이 넣을 서브 키워드
}