package com.rentjcar.blogai.dto;

import lombok.Getter;
import lombok.Setter;

// 저장된 분양 현장 1건 (키워드 분석 결과 단위)
@Getter
@Setter
public class EstateProjectDto {
    private String id;
    private String projectName;
    private String region;
    private String createdAt;
    private int postCount;
}