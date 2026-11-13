package com.rentjcar.blogai.service;

import com.rentjcar.blogai.account.ApiKeyEntry;
import com.rentjcar.blogai.account.AppUser;
import com.rentjcar.blogai.account.CurrentUserService;
import com.rentjcar.blogai.account.KeyCipher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gemini API 공통 호출 (부동산 / SEO / 차량 블로그 공용)
 *
 *  사용하는 키: 로그인한 회원이 직접 등록한 키
 *    (관리자는 본인 키가 없으면 서버 환경변수 GEMINI_API_KEY / GEMINI_API_KEY_2 사용)
 *  순서: 1번 키로 모델 4개 → 다 막히면 2번 키로 모델 4개
 *  - 429(한도 초과): 재시도 없이 바로 다음으로, 10분간 건너뜀
 *  - 503/500(혼잡): 2초 뒤 1번만 재시도, 안 되면 다음으로, 2분간 건너뜀
 *  - 404(모델 없음): 1시간 건너뜀
 *  - 403/402(키 문제): 그 키는 1시간 건너뜀
 *  - 전부 쉬는 중이면 전체를 다시 시도
 */
@Component
public class GeminiClient {

    @Value("${gemini.api.key:}")    // 관리자 전용 예비 (없어도 됨)
    private String serverKey1;

    @Value("${gemini.api.key2:}")
    private String serverKey2;

    private final CurrentUserService currentUser;
    private final KeyCipher cipher;

    public GeminiClient(CurrentUserService currentUser, KeyCipher cipher) {
        this.currentUser = currentUser;
        this.cipher = cipher;
    }

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

    /** "키지문|모델" → 이 시각까지 건너뛰기 (회원마다 키가 달라서 키 자체로 구분) */
    private final Map<String, Long> cooldownUntil = new ConcurrentHashMap<>();

    private volatile String lastModel = MODELS[0];
    /** 마지막으로 성공한 모델 (2번째 이후 키면 뒤에 "(N번 키)" 표시) */
    public String getLastModel() { return lastModel; }

    private static class Route {
        final int keyNo; final String key; final String model; final String fp;
        Route(int keyNo, String key, String model) {
            this.keyNo = keyNo; this.key = key; this.model = model; this.fp = fingerprint(key);
        }
        String id() { return fp + "|" + model; }
    }

    /** 지금 로그인한 회원이 쓸 키 목록 */
    private List<String> keysForCurrentUser() {
        AppUser u = currentUser.get();
        if (u == null) throw new RuntimeException("NO_API_KEY");
        List<String> keys = new ArrayList<>();
        if (u.hasApiKey()) {
            // 등록한 순서대로: 1번 키가 한도 초과/막힘이면 2번, 3번 키로 자동 전환
            for (ApiKeyEntry e : u.getApiKeys()) {
                String k = cipher.decrypt(e.getEnc());
                if (k != null) keys.add(k);
            }
            if (keys.isEmpty()) throw new RuntimeException("KEY_DECRYPT_FAILED");
        } else if (u.isAdmin()) {
            if (serverKey1 != null && !serverKey1.isBlank()) keys.add(serverKey1.trim());
            if (serverKey2 != null && !serverKey2.isBlank()) keys.add(serverKey2.trim());
        }
        if (keys.isEmpty()) throw new RuntimeException("NO_API_KEY");
        return keys;
    }

    public String generate(String prompt) {
        RuntimeException last = null;
        Set<String> deadKeys = new HashSet<>();               // 이번 요청에서 '키 자체가 안 됨'으로 판정된 키
        for (Route r : routesToTry(keysForCurrentUser())) {
            if (deadKeys.contains(r.fp)) continue;
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    String text = callOnce(r.key, r.model, prompt);
                    lastModel = r.model + (r.keyNo > 1 ? " (" + r.keyNo + "번 키)" : "");
                    cooldownUntil.remove(r.id());
                    return text;
                } catch (HttpStatusCodeException e) {
                    int code = e.getStatusCode().value();
                    if (code == 400) {
                        if (isInvalidKey(e)) {                 // 잘못된 키 → 이 키는 건너뛰고 다음 키로
                            last = new RuntimeException("INVALID_KEY");
                            deadKeys.add(r.fp);
                            for (String m : MODELS) rest(r.fp + "|" + m, COOLDOWN_LONG_MS);
                            break;
                        }
                        throw new RuntimeException("API_ERROR:400");   // 요청 자체 문제 → 바꿔도 소용없음
                    }
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
                        for (String m : MODELS) rest(r.fp + "|" + m, COOLDOWN_LONG_MS);
                        deadKeys.add(r.fp);
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
    private List<Route> routesToTry(List<String> keys) {
        List<Route> all = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) {
            for (String m : MODELS) all.add(new Route(i + 1, keys.get(i), m));
        }
        long now = System.currentTimeMillis();
        List<Route> ready = new ArrayList<>();
        for (Route r : all) {
            Long until = cooldownUntil.get(r.id());
            if (until == null || until <= now) ready.add(r);
        }
        return ready.isEmpty() ? all : ready;
    }

    // ─────────────────────────────────────────
    // 키 등록할 때 '실제로 되는 키인지' 확인
    //  OK / INVALID_KEY / QUOTA_EXCEEDED / FORBIDDEN / BUSY / NETWORK / API_ERROR:코드
    // ─────────────────────────────────────────
    public String testKey(String key) {
        // 글 생성(수십 초 걸림) 대신 '모델 목록 조회'로 빠르게 확인 → 몇 초 안에 끝나고 사용 한도도 안 씀
        try {
            String url = API_BASE.substring(0, API_BASE.length() - 1) + "?pageSize=1&key=" + key;
            ResponseEntity<Map> res = quickClient.getForEntity(url, Map.class);
            return res.getStatusCode().is2xxSuccessful() ? "OK" : "API_ERROR:" + res.getStatusCode().value();
        } catch (HttpStatusCodeException e) {
            int code = e.getStatusCode().value();
            if (code == 400) return isInvalidKey(e) ? "INVALID_KEY" : "API_ERROR:400";
            if (code == 401) return "INVALID_KEY";
            if (code == 429) return "QUOTA_EXCEEDED";
            if (code == 403) return "FORBIDDEN";
            if (code >= 500) return "BUSY";
            return "API_ERROR:" + code;
        } catch (ResourceAccessException e) {
            return "NETWORK";
        } catch (RuntimeException e) {
            return "API_ERROR";
        }
    }

    private static boolean isInvalidKey(HttpStatusCodeException e) {
        String body = e.getResponseBodyAsString();
        return body != null && (body.contains("API_KEY_INVALID") || body.contains("API key not valid"));
    }

    private static String fingerprint(String key) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h, 0, 6);
        } catch (Exception e) { return String.valueOf(key.hashCode()); }
    }

    private void rest(String id, long ms) {
        cooldownUntil.put(id, System.currentTimeMillis() + ms);
    }

    /** 키 확인용: 최대 10초 */
    private final RestTemplate quickClient = client(5_000, 10_000);
    /** 글 생성용: 긴 글도 기다리되, 무한정 멈추지는 않게 3분 */
    private final RestTemplate genClient = client(10_000, 180_000);

    private static RestTemplate client(int connectMs, int readMs) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(connectMs);
        f.setReadTimeout(readMs);
        return new RestTemplate(f);
    }

    @SuppressWarnings("unchecked")
    private String callOnce(String key, String model, String prompt) {
        RestTemplate restTemplate = genClient;
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
