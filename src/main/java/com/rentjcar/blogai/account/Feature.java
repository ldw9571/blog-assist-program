package com.rentjcar.blogai.account;

/** 회원별로 켜고 끌 수 있는 기능 */
public enum Feature {
    CAR("🚗 중고차 블로그 생성기", "/"),
    SEO("🍽️ 콘텐츠 블로그 도우미", "/seo"),
    ESTATE("🏢 분양 블로그 도우미", "/estate"),
    IMAGE("🖼️ 사진 변환 도구", "/images");

    private final String label;
    private final String path;

    Feature(String label, String path) { this.label = label; this.path = path; }
    public String getLabel() { return label; }
    public String getPath() { return path; }

    /** Gemini API 키가 있어야 쓸 수 있는 기능인지 (사진 변환은 AI를 안 써서 키 불필요) */
    public boolean needsApiKey() { return this != IMAGE; }

    /** 요청 주소가 어떤 기능에 속하는지 (해당 없으면 null) */
    public static Feature ofPath(String uri) {
        if (uri == null) return null;
        if (uri.equals("/") || uri.equals("/generate") || uri.equals("/generate-from-text")) return CAR;
        if (uri.equals("/seo") || uri.startsWith("/seo/")) return SEO;
        if (uri.equals("/estate") || uri.startsWith("/estate/")) return ESTATE;
        if (uri.equals("/images") || uri.startsWith("/images/")) return IMAGE;
        return null;
    }
}
