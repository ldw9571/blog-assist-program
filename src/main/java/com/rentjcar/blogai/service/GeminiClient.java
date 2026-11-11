package com.rentjcar.blogai.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gemini API 공통 호출 (부동산 / SEO / 차량 블로그 공용)
 *
 *  순서: 1번 키로 모델 4개 → 다 막히면 2번 키(GEMINI_API_KEY_2)로 모델 4개
 *  - 429(한도 초과): 재시도 없이 바로 다음으로, 10분간 건너뜀
 *  - 503/500(혼잡): 2초 뒤 1번만 재시도, 안 되면 다음으로, 2분간 건너뜀
 *  - 404(모델 없음): 1시간 건너뜀
 *  - 403/402(키 문제): 그 키는 1시간 건너뜀
 *  - 전부 쉬는 중이면 전체를 다시 시도
 */
@Component
public class GeminiClient {

    @Value("${gemini.api.key}")
    private String apiKey1;

    @Value("${gemini.api.key2:}")   // 없으면 빈 값 → 2번 키 사용 안 함
    private String apiKey2;

    private static final String[] MODELS = {
            "gemini-3.8-flash",     // 기본
            "gemini-3.7-flash",     // 예비 1
            "gemini-flash-latest",  // 예비 2 (최신 Flash 자동)
            "gemini-2.5-flash"      // 예비 3
    };
    private static final String API_BASE = "https://generativelanguage.googleapis.com/v1beta/models/";

    private static final long BUSY_RETRY_WAIT_MS = 2000;
    private static final long COOLDOWN_QUOTA_MS = 10 * 60 * 1000L;  // 429
    private static final long COOLDOWN_BUSY_MS  =  2 * 60 * 1000L;  // 503/500
    private static final long COOLDOWN_LONG_MS  = 60 * 60 * 1000L;  // 404, 403, 402

    /** "키번호|모델" → 이 시각까지 건너뛰기 */
    private final Map<String, Long> cooldownUntil = new ConcurrentHashMap<>();

    private volatile String lastModel = MODELS[0];
    /** 마지막으로 성공한 모델 (2번 키면 뒤에 "(예비키)" 표시) */
    public String getLastModel() { return lastModel; }

    private static class Route {
        final int keyNo; final String key; final String model;
        Route(int keyNo, String key, String model) { this.keyNo = keyNo; this.key = key; this.model = model; }
        String id() { return keyNo + "|" + model; }
    }

    public String generate(String prompt) {
        RuntimeException last = null;
        for (Route r : routesToTry()) {
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    String text = callOnce(r.key, r.model, prompt);
                    lastModel = r.model + (r.keyNo == 2 ? " (예비키)" : "");
                    cooldownUntil.remove(r.id());
                    return text;
                } catch (HttpStatusCodeException e) {
                    int code = e.getStatusCode().value();
                    if (code == 400) throw new RuntimeException("API_ERROR:400");   // 요청 자체 문제 → 바꿔도 소용없음
                    if (code >= 500 && attempt == 0) {        // 혼잡 → 1번만 다시
                        last = new RuntimeException("BUSY");
                        sleep(BUSY_RETRY_WAIT_MS);
                        continue;
                    }
                    if (code == 429)      { last = new RuntimeException("QUOTA_EXCEEDED"); rest(r.id(), COOLDOWN_QUOTA_MS); }
                    else if (code >= 500) { last = new RuntimeException("BUSY");           rest(r.id(), COOLDOWN_BUSY_MS); }
                    else if (code == 404) { last = new RuntimeException("API_ERROR:404");  rest(r.id(), COOLDOWN_LONG_MS); }
                    else if (code == 403 || code == 402) {     // 이 키 자체가 안 됨 → 이 키의 모든 모델 건너뜀
                        last = new RuntimeException(code == 403 ? "FORBIDDEN" : "PAYMENT_REQUIRED");
                        for (String m : MODELS) rest(r.keyNo + "|" + m, COOLDOWN_LONG_MS);
                    }
                    else                  { last = new RuntimeException("API_ERROR:" + code); }
                    break;                                     // 다음으로
                } catch (ResourceAccessException e) {          // 네트워크/타임아웃
                    last = new RuntimeException("NETWORK");
                    if (attempt == 0) { sleep(BUSY_RETRY_WAIT_MS); continue; }
                    break;
                }
            }
        }
        throw last != null ? last : new RuntimeException("API_ERROR");
    }

    /** 키1 모델들 → 키2 모델들 순서. 쉬는 중인 건 빼고, 전부 쉬는 중이면 전체 */
    private List<Route> routesToTry() {
        List<Route> all = new ArrayList<>();
        for (String m : MODELS) all.add(new Route(1, apiKey1, m));
        if (apiKey2 != null && !apiKey2.isBlank()) {
            for (String m : MODELS) all.add(new Route(2, apiKey2.trim(), m));
        }
        long now = System.currentTimeMillis();
        List<Route> ready = new ArrayList<>();
        for (Route r : all) {
            Long until = cooldownUntil.get(r.id());
            if (until == null || until <= now) ready.add(r);
        }
        return ready.isEmpty() ? all : ready;
    }

    private void rest(String id, long ms) {
        cooldownUntil.put(id, System.currentTimeMillis() + ms);
    }

    @SuppressWarnings("unchecked")
    private String callOnce(String key, String model, String prompt) {
        RestTemplate restTemplate = new RestTemplate();
        String url = API_BASE + model + ":generateContent?key=" + key;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> textPart = new HashMap<>();
        textPart.put("text", prompt);
        Map<String, Object> parts = new HashMap<>();
        parts.put("parts", Collections.singletonList(textPart));
        Map<String, Object> body = new HashMap<>();
        body.put("contents", Collections.singletonList(parts));

        ResponseEntity<Map> response = restTemplate.postForEntity(url, new HttpEntity<>(body, headers), Map.class);
        List<Map<String, Object>> candidates =
                (List<Map<String, Object>>) response.getBody().get("candidates");
        if (candidates == null || candidates.isEmpty()) throw new RuntimeException("EMPTY_RESPONSE");
        Map<String, Object> content = (Map<String, Object>) candidates.get(0).get("content");
        List<Map<String, Object>> resParts = (List<Map<String, Object>>) content.get("parts");
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> p : resParts) {
            Object t = p.get("text");
            if (t != null) sb.append(t);
        }
        return sb.toString();
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
