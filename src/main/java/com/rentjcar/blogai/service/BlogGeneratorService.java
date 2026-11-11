package com.rentjcar.blogai.service;

import com.rentjcar.blogai.dto.BlogRequestDto;
import com.rentjcar.blogai.dto.ParsedCarDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.http.*;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Service
public class BlogGeneratorService {

    @Value("${gemini.api.key}")
    private String apiKey;

    // Gemini 호출은 GeminiClient 공용 (한도 초과/혼잡 모델은 잠시 건너뜀)
    @Autowired
    private GeminiClient geminiClient;

    // ─────────────────────────────────────────
    // 모드 1: 폼 입력 → 블로그 생성
    // ─────────────────────────────────────────
    public String generateBlogPost(BlogRequestDto dto) {
        return callGemini(buildPrompt(dto));
    }

    // ─────────────────────────────────────────
    // 모드 2: 텍스트 → 파싱 후 블로그 생성
    // ─────────────────────────────────────────
    public String generateFromRawText(String rawText) {
        return callGemini(buildRawTextPrompt(rawText));
    }

    // ─────────────────────────────────────────
    // 모드 2: 텍스트 → ParsedCarDto 파싱
    // (블로그 생성과 별도 호출 - 견적서용)
    // ─────────────────────────────────────────
    public ParsedCarDto parseCarInfo(String rawText) {
        String json = callGemini(buildParsePrompt(rawText));
        return parseJsonToDto(json);
    }

    // ─────────────────────────────────────────
    // 폼 입력 프롬프트
    // ─────────────────────────────────────────
    private String buildPrompt(BlogRequestDto dto) {
        String fullModel = dto.getYear() + "년 " + dto.getBrand() + " "
                + dto.getCarModel() + " " + dto.getTrim();

        return "당신은 자동차 장기렌트 전문 블로그 작가입니다.\n"
                + "아래 중고 장기렌트 차량 정보를 바탕으로 네이버 블로그 포스팅 글을 작성해주세요.\n\n"
                + "【차량 정보】\n"
                + "- 관리번호: " + dto.getCarNo() + "\n"
                + "- 차량: " + fullModel + "\n"
                + "- 등록: " + dto.getRegDate() + "\n"
                + "- 주행거리: " + dto.getMileage() + "KM\n"
                + "- 사고이력: " + dto.getAccident() + "\n"
                + "- 색상: " + dto.getColor() + "\n"
                + "- 추가옵션: " + dto.getExtraOptions() + "\n"
                + "- 신차 출고가: " + dto.getNewPrice() + "원\n\n"
                + "【렌탈 조건】\n"
                + "- 담보(보증금): " + dto.getDeposit() + "\n"
                + "- 계약기간: " + dto.getContractPeriod() + "\n"
                + "- 인수 잔존가치(인수가): " + dto.getResidualValue() + "원\n"
                + "- 법인 (" + dto.getKmCorp() + "): 월 " + dto.getPriceCorpRate() + "원\n"
                + "- 개인사업자 (" + dto.getKmBiz() + "): 월 " + dto.getPriceBizRate() + "원\n"
                + "- 개인 (" + dto.getKmPersonal() + "): 월 " + dto.getPricePersonal() + "원\n\n"
                + "【포스팅 방향】\n"
                + "- 톤앤매너: " + dto.getTone() + "\n"
                + "- 강조 포인트: " + dto.getHighlight() + "\n"
                + "- 추가 메모: " + (dto.getMemo() != null ? dto.getMemo() : "없음") + "\n\n"
                + "【작성 규칙】\n"
                + "1. 전체 분량 약 1800~2200자 (한글 기준)\n"
                + "2. 구성: 도입부 → 차량 상세 → 렌탈 조건 → 가성비 분석 → 상담 유도\n"
                + "3. SEO 키워드 자연스럽게 포함 (예: " + dto.getBrand() + " " + dto.getCarModel() + " 장기렌트, 중고 장기렌트)\n"
                + "4. 이모지(😀🚗✅📞 등)나 별표(**) 같은 마크다운 강조 표시는 절대 사용하지 말고 순수 텍스트로만 작성\n"
                + "5. 글 전체적으로 AI가 쓴 티가 나지 않게 사람이 직접 쓴 것처럼 자연스러운 구어체로 작성\n"
                + "6. 마지막 줄에 '문의는 오픈채팅방으로 - https://open.kakao.com/o/gH9hMxAi' 포함\n";
    }

    // ─────────────────────────────────────────
    // 텍스트 → 블로그 프롬프트
    // ─────────────────────────────────────────
    private String buildRawTextPrompt(String rawText) {
        return "당신은 자동차 장기렌트 전문 블로그 작가입니다.\n"
                + "아래 텍스트는 중고 장기렌트 차량의 정보입니다. 내용을 분석해서 네이버 블로그 포스팅 글을 작성해주세요.\n\n"
                + "【입력된 차량 정보 텍스트】\n"
                + rawText + "\n\n"
                + "【작성 규칙】\n"
                + "1. 전체 분량 약 1800~2200자 (한글 기준)\n"
                + "2. 구성: 도입부 → 차량 상세 → 렌탈 조건 → 가성비 분석 → 상담 유도\n"
                + "3. 위 텍스트에서 차종명을 파악해 SEO 키워드 자연스럽게 포함\n"
                + "4. 법인 / 개인사업자 / 개인 렌탈료를 비교표 형식으로 정리\n"
                + "5. 이모지(😀🚗✅📞 등)나 별표(**) 같은 마크다운 강조 표시는 절대 사용하지 말고 순수 텍스트로만 작성\n"
                + "6. 글 전체적으로 AI가 쓴 티가 나지 않게 사람이 직접 쓴 것처럼 자연스러운 구어체로 작성\n"
                + "7. 마지막 줄에 '문의는 오픈채팅방으로 - https://open.kakao.com/o/gH9hMxAi' 포함\n"
                + "8. 글 안의 모든 금액은 세 자리마다 콤마 표기 (예: 1,000,000원)\n"
                + "9. 글만 출력하고 다른 설명은 하지 마세요.\n";
    }

    // ─────────────────────────────────────────
    // 텍스트 → JSON 파싱 프롬프트 (견적서용)
    // ─────────────────────────────────────────
    private String buildParsePrompt(String rawText) {
        return "아래 중고차 장기렌탈 정보 텍스트에서 각 항목을 추출해 JSON으로만 응답하세요.\n"
                + "다른 설명, 마크다운 코드블록 없이 순수 JSON만 출력하세요.\n\n"
                + "【입력 텍스트】\n" + rawText + "\n\n"
                + "【출력 형식】\n"
                + "{\n"
                + "  \"carNo\": \"관리번호\",\n"
                + "  \"brand\": \"제조사(예:제네시스,현대,기아,BMW등)\",\n"
                + "  \"carModel\": \"모델명(예:G80)\",\n"
                + "  \"trim\": \"트림/엔진(예:2.5T 가솔린 AWD)\",\n"
                + "  \"year\": \"연식(4자리 숫자, 예:2023)\",\n"
                + "  \"regDate\": \"등록월(예:23년02월식)\",\n"
                + "  \"mileage\": \"주행거리(숫자+단위, 예:81,704KM)\",\n"
                + "  \"accident\": \"사고이력(예:무사고)\",\n"
                + "  \"color\": \"색상\",\n"
                + "  \"extraOptions\": \"추가옵션\",\n"
                + "  \"newPrice\": \"신차가(숫자만, 예:63,720,000)\",\n"
                + "  \"deposit\": \"담보(예:무보증)\",\n"
                + "  \"contractPeriod\": \"계약기간(예:12개월)\",\n"
                + "  \"residualValue\": \"잔존가치(숫자만, 예:32,982,000)\",\n"
                + "  \"priceCorpRate\": \"법인 월렌탈료(숫자만, 예:670,800)\",\n"
                + "  \"kmCorp\": \"법인 약정KM(예:연간 1만KM)\",\n"
                + "  \"priceBizRate\": \"개인사업자 월렌탈료(숫자만)\",\n"
                + "  \"kmBiz\": \"개인사업자 약정KM\",\n"
                + "  \"pricePersonal\": \"개인 월렌탈료(숫자만)\",\n"
                + "  \"kmPersonal\": \"개인 약정KM\"\n"
                + "}\n"
                + "없는 항목은 빈 문자열로 두세요.";
    }

    // ─────────────────────────────────────────
    // JSON 문자열 → ParsedCarDto 변환 (직접 파싱)
    // ─────────────────────────────────────────
    private ParsedCarDto parseJsonToDto(String json) {
        ParsedCarDto dto = new ParsedCarDto();
        if (json == null) return dto;

        // 마크다운 코드블록 제거 (혹시 모를 경우 대비)
        json = json.replaceAll("```json", "").replaceAll("```", "").trim();

        dto.setCarNo(extractJson(json, "carNo"));
        dto.setBrand(extractJson(json, "brand"));
        dto.setCarModel(extractJson(json, "carModel"));
        dto.setTrim(extractJson(json, "trim"));
        dto.setYear(extractJson(json, "year"));
        dto.setRegDate(extractJson(json, "regDate"));
        dto.setMileage(extractJson(json, "mileage"));
        dto.setAccident(extractJson(json, "accident"));
        dto.setColor(extractJson(json, "color"));
        dto.setExtraOptions(extractJson(json, "extraOptions"));
        dto.setNewPrice(extractJson(json, "newPrice"));
        dto.setDeposit(extractJson(json, "deposit"));
        dto.setContractPeriod(extractJson(json, "contractPeriod"));
        dto.setResidualValue(extractJson(json, "residualValue"));
        dto.setPriceCorpRate(extractJson(json, "priceCorpRate"));
        dto.setKmCorp(extractJson(json, "kmCorp"));
        dto.setPriceBizRate(extractJson(json, "priceBizRate"));
        dto.setKmBiz(extractJson(json, "kmBiz"));
        dto.setPricePersonal(extractJson(json, "pricePersonal"));
        dto.setKmPersonal(extractJson(json, "kmPersonal"));
        return dto;
    }

    // JSON 문자열에서 키 값 추출
    private String extractJson(String json, String key) {
        try {
            String pattern = "\"" + key + "\"";
            int idx = json.indexOf(pattern);
            if (idx < 0) return "";
            int colon = json.indexOf(':', idx);
            int start = colon + 1;
            while (start < json.length() && json.charAt(start) == ' ') start++;
            char first = json.charAt(start);
            if (first == '"') {
                int end = json.indexOf('"', start + 1);
                return json.substring(start + 1, end);
            }
            return "";
        } catch (Exception e) {
            return "";
        }
    }

    private String callGemini(String prompt) {
        return geminiClient.generate(prompt);
    }
}
