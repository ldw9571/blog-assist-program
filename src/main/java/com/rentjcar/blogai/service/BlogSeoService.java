package com.rentjcar.blogai.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.http.*;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class BlogSeoService {

    @Value("${gemini.api.key}")
    private String geminiApiKey;

    // Gemini 호출은 GeminiClient 공용 (한도 초과/혼잡 모델은 잠시 건너뜀)
    @Autowired
    private GeminiClient geminiClient;

    // 소제목 마커 패턴: @@소제목 내용@@
    private static final Pattern SUBHEADING_PATTERN = Pattern.compile("@@\\s*(.+?)\\s*@@");

    // 이모지/이모티콘 범위 (주요 유니코드 블록)
    private static final Pattern EMOJI_PATTERN = Pattern.compile(
            "[\\x{1F300}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{2B00}-\\x{2BFF}" +
                    "\\x{FE0F}\\x{1F1E6}-\\x{1F1FF}\\x{2190}-\\x{21FF}]"
    );

    // SEO 점수 추출용
    private static final Pattern SCORE_PATTERN = Pattern.compile("\\[SEO 점수\\]\\s*(\\d+)");

    // ─────────────────────────────────────────
    // SEO 분석 (1회)
    // ─────────────────────────────────────────
    public String analyze(String keyword, String title, String content) {
        return callGemini(buildAnalyzePrompt(keyword, title, content));
    }

    // ─────────────────────────────────────────
    // SEO 분석 - N회 반복 후 최고 점수 채택
    // ─────────────────────────────────────────
    public String analyzeBest(String keyword, String title, String content, int rounds) {
        int safeRounds = Math.max(1, Math.min(rounds, 5));

        String bestResult = null;
        int bestScore = -1;
        List<Integer> scoreHistory = new ArrayList<>();

        for (int i = 0; i < safeRounds; i++) {
            String result;
            try {
                result = callGemini(buildAnalyzePrompt(keyword, title, content));
            } catch (RuntimeException e) {
                // 한 회차라도 성공했으면 나머지 실패는 건너뛰고, 전부 실패면 오류 그대로 전달
                if (bestResult == null && i == safeRounds - 1) throw e;
                scoreHistory.add(-1);
                continue;
            }
            int score = extractScore(result);
            scoreHistory.add(score);
            if (score > bestScore) {
                bestScore = score;
                bestResult = result;
            }
        }

        if (bestResult == null) {
            bestResult = "분석 결과를 가져오지 못했습니다.";
        }

        if (safeRounds > 1) {
            StringBuilder note = new StringBuilder();
            note.append("[분석 반복 안내]\n");
            note.append("총 ").append(safeRounds).append("회 분석 중 최고 점수(")
                    .append(bestScore).append("점) 결과를 보여드려요.\n");
            note.append("회차별 점수: ");
            for (int i = 0; i < scoreHistory.size(); i++) {
                int sc = scoreHistory.get(i);
                note.append((i + 1)).append("회차 ").append(sc < 0 ? "실패(서버 혼잡)" : sc + "점");
                if (i < scoreHistory.size() - 1) note.append(" / ");
            }
            note.append("\n\n");
            bestResult = note + bestResult;
        }

        return bestResult;
    }

    // ─────────────────────────────────────────
    // 포스팅 가이드라인 + 완성본 생성 (다중 카테고리 지원)
    // ─────────────────────────────────────────
    public String generateGuideAndPost(String keyword, String postType, String postTypeOther, String name,
                                       String address, String phone,
                                       String hours, String menu, String myReview) {
        return callGemini(buildGuideAndPostPrompt(keyword, postType, postTypeOther, name, address, phone,
                hours, menu, myReview));
    }

    // ─────────────────────────────────────────
    // 소제목 마커 → HTML 볼드(19px) 변환 + 금지기호/이모지 제거 (화면 표시용)
    // ─────────────────────────────────────────
    public String formatPostAsHtml(String rawPost) {
        if (rawPost == null || rawPost.isEmpty()) return "";
        String escaped = escapeHtml(rawPost);
        String withHeadings = SUBHEADING_PATTERN.matcher(escaped)
                .replaceAll("<b style=\"font-size:19px;\">$1</b>");
        String cleaned = sanitizeLines(withHeadings, true);
        return cleaned.replace("\n", "<br>");
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ─────────────────────────────────────────
    // 서식 없는 순수 텍스트 (복사 대체용)
    // ─────────────────────────────────────────
    public String formatPostAsPlain(String rawPost) {
        if (rawPost == null || rawPost.isEmpty()) return "";
        String withoutMarker = SUBHEADING_PATTERN.matcher(rawPost).replaceAll("$1");
        return sanitizeLines(withoutMarker, false);
    }

    // ─────────────────────────────────────────
    // 줄 단위로 금지 기호(글머리 -, *) 및 이모지 제거
    // 해시태그 줄(#으로만 이루어진 줄)은 그대로 유지
    // isHtml=true 인 경우 이미 삽입된 <b style=...> 태그는 건드리지 않음
    // ─────────────────────────────────────────
    private String sanitizeLines(String text, boolean isHtml) {
        String[] lines = text.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            boolean isHashtagLine = trimmed.matches("^(#\\S+\\s*){1,}$");

            String result = line;
            if (!isHashtagLine) {
                // 글머리 기호로 쓰인 "- " 또는 "* " 만 제거 (문장 중간 하이픈은 보존)
                result = result.replaceFirst("^(\\s*)[-*]\\s+", "$1");
                // 강조용으로 남은 단독 * 제거
                result = result.replace("*", "");
                // 해시태그 줄이 아니면 # 전부 제거
                result = result.replace("#", "");
                // 이모지 제거
                result = EMOJI_PATTERN.matcher(result).replaceAll("");
                // 중복 공백 정리
                result = result.replaceAll(" {2,}", " ");
            }
            sb.append(result);
            if (i < lines.length - 1) sb.append("\n");
        }
        return sb.toString().trim();
    }

    private int extractScore(String result) {
        try {
            Matcher m = SCORE_PATTERN.matcher(result);
            if (m.find()) return Integer.parseInt(m.group(1));
        } catch (Exception ignored) {}
        return 0;
    }

    // ─────────────────────────────────────────
    // 포스팅 유형별 페르소나
    // ─────────────────────────────────────────
    private String personaFor(String postType) {
        if (postType == null) return "10년 경력의 블로거";
        switch (postType) {
            case "맛집": return "10년 경력의 맛집 블로거";
            case "여행": return "10년 경력의 여행 전문 블로거";
            case "숙소": return "10년 경력의 숙소·호텔 전문 블로거";
            case "체험": return "10년 경력의 체험·액티비티 전문 블로거";
            case "일상": return "10년 경력의 일상 공감 블로거";
            default: return "10년 경력의 블로거";
        }
    }

    // ─────────────────────────────────────────
    // 포스팅 유형별 대상 명칭
    // ─────────────────────────────────────────
    private String subjectLabelFor(String postType) {
        if (postType == null) return "장소";
        switch (postType) {
            case "맛집": return "식당";
            case "여행": return "장소/명소";
            case "숙소": return "숙소";
            case "체험": return "체험/클래스";
            case "일상": return "장소/주제";
            default: return "장소";
        }
    }

    // ─────────────────────────────────────────
    // SEO 분석 프롬프트
    // ─────────────────────────────────────────
    private String buildAnalyzePrompt(String keyword, String title, String content) {
        return "너는 네이버 블로그 SEO 컨설턴트이자 콘텐츠 전문 에디터다.\n"
                + "아래 블로그 글을 분석해서 항목별로 평가해줘.\n\n"
                + "목표:\n"
                + "- 네이버 검색 상위 노출\n"
                + "- 자연스러운 후기\n"
                + "- 광고 느낌 최소화\n"
                + "- 실제 방문/경험 후기처럼 작성\n\n"
                + "【입력 정보】\n"
                + "메인 키워드: " + keyword + "\n"
                + "제목: " + title + "\n"
                + "본문:\n" + content + "\n\n"
                + "【최종 업로드 버전 작성 시 반드시 지킬 것】\n"
                + "1. [매우 중요] 본문에는 하이픈(-), 샵(#), 별표(*) 기호를 절대 사용하지 마라. "
                +    "글 맨 마지막 해시태그 줄에서만 #을 사용할 수 있다.\n"
                + "2. [매우 중요] 이모지/이모티콘을 절대 사용하지 마라.\n"
                + "3. [매우 중요] 소제목은 반드시 '@@소제목 내용@@' 형태로 앞뒤에 @@ 기호를 붙여서 표시해라.\n\n"
                + "【출력 형식 - 반드시 아래 형식 그대로】\n"
                + "[SEO 점수] 00점 / 100점\n\n"
                + "[잘된 점]\n* 내용\n\n"
                + "[수정이 필요한 점]\n* 내용\n\n"
                + "[수정 추천 문장]\n기존: \n수정: \n\n"
                + "[삭제하면 좋은 문장]\n* 내용\n\n"
                + "[추가하면 좋은 문장]\n* 내용\n\n"
                + "[추천 키워드 & 활용법]\n"
                + "▶ 메인 키워드: (제목/도입부 활용)\n"
                + "▶ 서브 키워드 3개: 1. 2. 3. (본문 중간 분산 배치)\n"
                + "▶ 롱테일 키워드 3개: 1. 2. 3. (소제목/결론부 활용)\n\n"
                + "[상위노출 가능성 평가]\n내용\n\n"
                + "[최종 업로드 버전]\n(SEO 최적화된 본문 전체 다시 작성 - 위 작성 규칙 반드시 준수)\n\n"
                + "[추천 제목 TOP 10]\n1. 2. 3. 4. 5. 6. 7. 8. 9. 10.\n\n"
                + "[최종 의견]\n"
                + "이 글이 어떤 키워드로 노출될 가능성이 높은지 설명.\n"
                + "상위 노출을 위한 가장 중요한 수정사항 3가지 설명.\n";
    }

    // ─────────────────────────────────────────
    // 가이드라인 + 포스팅 완성본 프롬프트 (다중 카테고리 지원)
    // ─────────────────────────────────────────
    private String buildGuideAndPostPrompt(String keyword, String postType, String postTypeOther, String name,
                                           String address, String phone,
                                           String hours, String menu, String myReview) {
        String typeLabel;
        String persona;
        String subject;

        if ("기타".equals(postType) && postTypeOther != null && !postTypeOther.trim().isEmpty()) {
            typeLabel = postTypeOther.trim();
            persona = "10년 경력의 " + typeLabel + " 전문 블로거";
            subject = typeLabel;
        } else {
            typeLabel = (postType == null || postType.isEmpty()) ? "맛집" : postType;
            persona = personaFor(postType);
            subject = subjectLabelFor(postType);
        }

        return "너는 네이버 블로그 SEO 전문가이자 " + persona + "야.\n"
                + "아래 " + subject + " 정보와 실제 경험 후기를 바탕으로 ① 포스팅 가이드라인과 ② 실제 블로그 포스팅 완성본을 작성해줘.\n\n"
                + "【포스팅 유형】" + typeLabel + "\n\n"
                + "【" + subject + " 정보】\n"
                + "이름/상호: " + name + "\n"
                + "위치: " + address + "\n"
                + "연락처: " + (phone == null || phone.isEmpty() ? "없음" : phone) + "\n"
                + "이용시간: " + (hours == null || hours.isEmpty() ? "없음" : hours) + "\n"
                + "대표 메뉴/상품: " + (menu == null || menu.isEmpty() ? "없음" : menu) + "\n"
                + "메인 키워드: " + keyword + "\n\n"
                + "【실제 경험 후기 (이 내용을 자연스럽게 녹여서 써줘)】\n"
                + (myReview == null || myReview.isEmpty() ? "없음" : myReview) + "\n\n"

                + "【포스팅 완성본 작성 규칙 - 반드시 지킬 것】\n"
                + "1. 전체 분량 1500~2000자\n"
                + "2. 구어체, 친근한 말투 (예: ~했어요, ~더라고요, ~이에요)\n"
                + "3. '~습니다' 체 절대 금지, 구어체로만\n"
                + "4. 문장이 너무 길지 않게 (2~3줄마다 줄바꿈)\n"
                + "5. 도입부 첫 문장에 키워드 자연스럽게 포함\n"
                + "6. 위치, 이용시간, 대표메뉴/상품, 가격 정보 자연스럽게 녹이기\n"
                + "7. 마무리에 재방문/재이용 의사 자연스럽게 표현\n"
                + "8. 문장마다 같은 패턴 반복 금지\n"
                + "9. 광고성 문구 절대 금지 (협찬, 내돈내산 강조 금지)\n"
                + "10. 실제로 경험한 사람처럼 생생하게\n"
                + "11. [매우 중요] 본문 전체에서 하이픈(-), 샵(#), 별표(*) 기호를 절대 사용하지 마라. "
                +     "글 맨 마지막 해시태그 줄에서만 #을 사용할 수 있다.\n"
                + "12. [매우 중요] 이모지/이모티콘을 절대 사용하지 마라. AI가 쓴 것처럼 보이는 요소이므로 완전히 배제한다.\n"
                + "13. [매우 중요] 소제목(문단을 나누는 짧은 제목)은 반드시 '@@소제목 내용@@' 형태로 앞뒤에 @@ 기호를 붙여서 표시해라. "
                +     "예: @@OO역 3번 출구, 5분이면 도착!@@ 소제목은 본문 전체에 3~5개 정도 자연스럽게 배치한다.\n\n"

                + "【출력 형식 - 반드시 아래 구분자 그대로】\n"
                + "===GUIDE_START===\n"
                + "[추천 제목 5개]\n"
                + "1. (제목)\n2. (제목)\n3. (제목)\n4. (제목)\n5. (제목)\n\n"
                + "[추천 키워드 & 활용법]\n"
                + "▶ 메인 키워드: (제목/도입부에 사용)\n"
                + "▶ 서브 키워드 3개: 1. 2. 3. (본문 중간에 자연스럽게 분산 배치)\n"
                + "▶ 롱테일 키워드 3개: 1. 2. 3. (소제목이나 결론부에 활용)\n\n"
                + "[포스팅 구성 가이드]\n"
                + "▶ 도입부: (가이드)\n"
                + "▶ 기본 정보: (가이드)\n"
                + "▶ 메뉴/상품 소개: (가이드)\n"
                + "▶ 경험 & 분위기: (가이드)\n"
                + "▶ 사진 배치: (가이드)\n"
                + "▶ 마무리: (가이드)\n\n"
                + "[꼭 넣어야 할 정보]\n"
                + "□ 항목\n\n"
                + "[피해야 할 표현]\n"
                + "* 내용\n"
                + "===GUIDE_END===\n"
                + "===POST_START===\n"
                + "(블로그 포스팅 완성본 전체 - 소제목은 @@ @@ 로 표시, 하이픈/샵/별표/이모지 절대 금지, 맨 마지막 줄에 관련 해시태그 8~10개)\n"
                + "===POST_END===\n";
    }

    private String callGemini(String prompt) {
        return geminiClient.generate(prompt);
    }
}
