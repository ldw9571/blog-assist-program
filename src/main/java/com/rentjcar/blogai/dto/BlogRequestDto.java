package com.rentjcar.blogai.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class BlogRequestDto {

    // 차량 기본 정보
    private String carNo;           // 관리번호 (예: 191호6313)
    private String brand;           // 제조사
    private String carModel;        // 모델명
    private String trim;            // 트림/엔진
    private String year;            // 연식
    private String regDate;         // 등록월 (예: 23년02월식)
    private String mileage;         // 주행거리
    private String accident;        // 사고유무
    private String color;           // 색상
    private String extraOptions;    // 추가 옵션
    private String newPrice;        // 신차 출고가

    // 렌탈 조건
    private String deposit;         // 담보/보증금
    private String contractPeriod;  // 계약기간
    private String residualValue;   // 인수 잔존가치(인수가)

    // 고객 유형별 월렌탈료
    private String priceCorpRate;   // 법인 월렌탈료
    private String kmCorp;          // 법인 약정 KM
    private String priceBizRate;    // 개인사업자 월렌탈료
    private String kmBiz;           // 개인사업자 약정 KM
    private String pricePersonal;   // 개인 월렌탈료
    private String kmPersonal;      // 개인 약정 KM

    // 포스팅 설정
    private String tone;            // 톤앤매너
    private String highlight;       // 강조 포인트
    private String memo;            // 추가 메모

    // 텍스트 붙여넣기 모드용
    private String rawText;
}