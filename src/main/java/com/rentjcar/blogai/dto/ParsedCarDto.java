package com.rentjcar.blogai.dto;

import lombok.Getter;
import lombok.Setter;

// 텍스트 붙여넣기 모드에서 AI가 파싱한 결과를 담는 DTO
@Getter
@Setter
public class ParsedCarDto {
    private String carNo;
    private String brand;
    private String carModel;
    private String trim;
    private String year;
    private String regDate;
    private String mileage;
    private String accident;
    private String color;
    private String extraOptions;
    private String newPrice;
    private String deposit;
    private String contractPeriod;
    private String residualValue;
    private String priceCorpRate;
    private String kmCorp;
    private String priceBizRate;
    private String kmBiz;
    private String pricePersonal;
    private String kmPersonal;
}