package com.rentjcar.blogai.service;

import com.rentjcar.blogai.dto.EstateKeywordDto;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 분양(오피스텔/도시형생활주택/상가) 홍보 블로그 도우미
 *  1) 분양 자료 PDF → 텍스트 추출
 *  2) 자료 분석 → 네이버 노출용 키워드 + 포스팅 주제 추천
 *  3) 선택한 키워드/주제 → 가이드 + 포스팅 완성본 생성
 *  4) 완성본 → 네이버 정보성 글 노출 점검 (점수 + 고칠 점)
 */
@Service
public class EstateBlogService {

    @Value("${gemini.api.key}")
    private String geminiApiKey;

    // 소제목 마커 패턴: @@소제목 내용@@
    private static final Pattern SUBHEADING_PATTERN = Pattern.compile("@@\\s*(.+?)\\s*@@");
    // 이모지 범위
    private static final Pattern EMOJI_PATTERN = Pattern.compile(
            "[\\x{1F300}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{2B00}-\\x{2BFF}" +
                    "\\x{FE0F}\\x{1F1E6}-\\x{1F1FF}\\x{2190}-\\x{21FF}]");


    /** 고정 상담 안내 (체크 시 글 끝 해시태그 위에 코드로 그대로 붙임 - AI가 번호를 틀리지 않게) */
    public static final String FIXED_CONTACT =
            "분양상담 010-4713-9571\n"
                    + "카카오 오픈채팅 https://open.kakao.com/o/sNZkTTTi\n"
                    + "연락 주실 때 '블로그 보고 연락드렸어요'라고 말씀해 주시면 더 빠르게 안내해 드릴게요.";

    /** 고정 안내를 쓸 때 AI에게 주는 지시 */
    public static final String FIXED_CONTACT_PROMPT =
            "글 맨 끝에 상담 연락처가 자동으로 붙는다. 본문에는 전화번호, 링크, '문의 주세요' 같은 연락 유도 문구를 절대 쓰지 말고, "
                    + "마지막 문단은 '궁금한 점은 아래 상담 안내로 편하게 물어보세요' 정도로 자연스럽게 마무리만 해라.";

    /** 프롬프트에 넣을 자료 최대 길이 (너무 길면 응답이 느려짐) */
    private static final int MAX_MATERIAL_CHARS = 60000;

    // ─────────────────────────────────────────
    // 1) PDF → 텍스트
    // ─────────────────────────────────────────
    public String extractPdfText(MultipartFile file) {
        if (file == null || file.isEmpty()) return "";
        try (PDDocument doc = Loader.loadPDF(file.getBytes())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return cleanMaterial(stripper.getText(doc));
        } catch (IOException e) {
            throw new RuntimeException("PDF_READ_FAILED");
        }
    }

    /**
     * 교육자료 PDF 특성상 매 페이지 반복되는 머리말/면책문구가 많아서
     * 3회 이상 반복되는 긴 줄은 1번만 남기고, 공백을 정리한다.
     */
    public String cleanMaterial(String raw) {
        if (raw == null) return "";
        String[] lines = raw.replace("\r", "").split("\n");
        Map<String, Integer> count = new HashMap<>();
        for (String l : lines) {
            String k = l.trim().replaceAll("\\s+", " ");
            if (!k.isEmpty()) count.merge(k, 1, Integer::sum);
        }
        Set<String> printedRepeated = new HashSet<>();
        StringBuilder sb = new StringBuilder();
        for (String l : lines) {
            String k = l.trim().replaceAll("\\s+", " ");
            if (k.isEmpty()) continue;
            // 짧은 값(면적 숫자, 호수 등)은 표 데이터라 남기고, 긴 반복 문장(머리말/면책문구)만 1회로
            if (k.length() >= 15 && count.get(k) >= 3) {
                if (!printedRepeated.add(k)) continue;
            }
            sb.append(k).append("\n");
        }
        return sb.toString().trim();
    }

    private String clip(String material) {
        if (material == null) return "";
        return material.length() > MAX_MATERIAL_CHARS
                ? material.substring(0, MAX_MATERIAL_CHARS) + "\n...(이하 생략)"
                : material;
    }

    // ─────────────────────────────────────────
    // 2) 키워드 + 포스팅 주제 추천
    // ─────────────────────────────────────────
    public String recommendKeywords(String projectName, String region, String material) {
        return callGemini(buildKeywordPrompt(projectName, region, clip(material)));
    }

    /** 응답의 ===KEYWORDS_START=== 블록을 클릭 가능한 칩용 리스트로 변환 */
    public List<EstateKeywordDto> parseKeywordTable(String raw) {
        List<EstateKeywordDto> list = new ArrayList<>();
        if (raw == null) return list;
        int s = raw.indexOf("===KEYWORDS_START===");
        int e = raw.indexOf("===KEYWORDS_END===");
        if (s < 0 || e <= s) return list;
        String block = raw.substring(s + "===KEYWORDS_START===".length(), e);
        for (String line : block.split("\n")) {
            String[] cols = line.trim().split("\\|");
            if (cols.length < 4) continue;
            String kw = cols[0].trim();
            if (kw.isEmpty() || kw.startsWith("키워드")) continue;
            EstateKeywordDto dto = new EstateKeywordDto();
            dto.setKeyword(kw);
            dto.setLevel(cols[1].trim());
            dto.setAngle(cols[2].trim());
            dto.setTitleIdea(cols[3].trim());
            dto.setSubKeywords(cols.length > 4 ? cols[4].trim() : "");
            list.add(dto);
        }
        return list;
    }

    /** 화면에 보여줄 분석 리포트 (칩용 원본 블록은 제거) */
    public String stripKeywordTable(String raw) {
        if (raw == null) return "";
        int s = raw.indexOf("===KEYWORDS_START===");
        int e = raw.indexOf("===KEYWORDS_END===");
        if (s < 0 || e <= s) return raw.trim();
        return (raw.substring(0, s) + raw.substring(e + "===KEYWORDS_END===".length())).trim();
    }

    // ─────────────────────────────────────────
    // 3) 가이드 + 포스팅 완성본
    // ─────────────────────────────────────────
    public String generatePost(String keyword, String subKeywords, String angle, String projectName,
                               String contact, String tone, String memo, String material,
                               String prevPost, String seoFeedback) {
        return callGemini(buildPostPrompt(keyword, subKeywords, angle, projectName,
                contact, tone, memo, clip(material), prevPost, seoFeedback));
    }

    // ─────────────────────────────────────────
    // 4) 노출 점검
    // ─────────────────────────────────────────
    private static final Pattern SCORE_PATTERN = Pattern.compile("\\[노출 점수\\]\\s*(\\d{1,3})");

    public String checkSeo(String keyword, String subKeywords, String post) {
        return callGemini(buildSeoCheckPrompt(keyword, subKeywords, post));
    }

    public int extractScore(String seoResult) {
        if (seoResult == null) return -1;
        Matcher m = SCORE_PATTERN.matcher(seoResult);
        return m.find() ? Math.min(100, Integer.parseInt(m.group(1))) : -1;
    }

    // ─────────────────────────────────────────
    // 결과 후처리: 소제목 굵게(19px) + 금지기호/이모지 제거
    // ─────────────────────────────────────────
    /**
     * 고정 상담 안내를 해시태그 줄 바로 위에 붙인다.
     * 수정본처럼 이미 안내가 들어있으면 먼저 지우고 다시 붙여서 중복을 막는다.
     */
    public String appendContact(String post) {
        if (post == null) post = "";
        List<String> lines = new ArrayList<>();
        for (String l : post.split("\n", -1)) {
            if (l.contains("010-4713-9571") || l.contains("open.kakao.com/o/sNZkTTTi") || l.contains("블로그 보고 연락")) continue;
            lines.add(l);
        }
        // 맨 끝 빈 줄 정리
        while (!lines.isEmpty() && lines.get(lines.size() - 1).trim().isEmpty()) lines.remove(lines.size() - 1);
        int insertAt = lines.size();
        if (!lines.isEmpty() && lines.get(lines.size() - 1).trim().startsWith("#")) insertAt = lines.size() - 1;

        List<String> block = new ArrayList<>();
        block.add("");
        block.addAll(Arrays.asList(FIXED_CONTACT.split("\n")));
        block.add("");
        lines.addAll(insertAt, block);

        // 빈 줄이 2줄 이상 연속되면 1줄로 정리
        List<String> out = new ArrayList<>();
        for (String l : lines) {
            boolean blank = l.trim().isEmpty();
            if (blank && !out.isEmpty() && out.get(out.size() - 1).trim().isEmpty()) continue;
            out.add(l);
        }
        return String.join("\n", out);
    }

    public String formatPostAsHtml(String rawPost) {
        if (rawPost == null || rawPost.isEmpty()) return "";
        String escaped = rawPost.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        String withHeadings = SUBHEADING_PATTERN.matcher(escaped)
                .replaceAll("<b style=\"font-size:19px;\">$1</b>");
        return sanitizeLines(withHeadings).replace("\n", "<br>");
    }

    public String formatPostAsPlain(String rawPost) {
        if (rawPost == null || rawPost.isEmpty()) return "";
        return sanitizeLines(SUBHEADING_PATTERN.matcher(rawPost).replaceAll("$1"));
    }

    private String sanitizeLines(String text) {
        String[] lines = text.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (!line.trim().matches("^(#\\S+\\s*){1,}$")) {      // 해시태그 줄은 유지
                line = line.replaceFirst("^(\\s*)[-*]\\s+", "$1");
                line = line.replace("*", "").replace("#", "");
                line = EMOJI_PATTERN.matcher(line).replaceAll("");
                line = line.replaceAll(" {2,}", " ");
            }
            sb.append(line);
            if (i < lines.length - 1) sb.append("\n");
        }
        return sb.toString().trim();
    }

    // ─────────────────────────────────────────
    // 공통 작성 원칙 (부동산 광고 리스크 방지)
    // ─────────────────────────────────────────
    private static final String COMPLIANCE_RULES =
            "【부동산 광고 주의사항 - 반드시 지킬 것】\n"
                    + "1. '수익 보장', '확정 수익', '무조건 오른다', '프리미엄 확실' 같은 수익·시세 단정 표현 절대 금지. "
                    +    "수익률/시세는 '자료 기준', '인근 시세 참고' 처럼 출처와 함께 참고 정보로만 쓴다.\n"
                    + "2. 개발호재(지하철 2호선, 산업단지, 복합쇼핑몰, 재개발 등)는 '예정', '추진 중', '계획'으로 표현하고 확정된 것처럼 쓰지 않는다.\n"
                    + "3. 교육자료 내부 정보(판매완료/잔여 호실 수, 수수료, 영업 전략, '외부 유출 금지' 문구 등)는 글에 절대 쓰지 않는다.\n"
                    + "4. 자료에 없는 분양가, 계약 조건, 혜택은 지어내지 않는다. 모르면 '상담 시 안내'로 처리한다.\n"
                    + "5. 대출/정책(보금자리론, LTV 등)은 '대상 요건 충족 시', '시행 예정' 등 조건을 함께 쓴다.\n";

    // ─────────────────────────────────────────
    // 키워드 추천 프롬프트
    // ─────────────────────────────────────────
    private String buildKeywordPrompt(String projectName, String region, String material) {
        return "너는 네이버 블로그 부동산(분양) 마케팅 SEO 전문가야.\n"
                + "아래 분양 현장 자료를 분석해서, 네이버 검색(블로그/VIEW 탭)에 노출될 만한 키워드를 뽑고 "
                + "키워드별로 쓸 포스팅 주제까지 기획해줘.\n\n"
                + "【현장명】" + nz(projectName, "자료에서 파악") + "\n"
                + "【지역/대표 검색어 힌트】" + nz(region, "자료에서 파악") + "\n\n"
                + "【분양 자료 원문】\n" + material + "\n\n"
                + "【키워드 선정 기준】\n"
                + "1. 실제 사람이 네이버에 칠 법한 검색어 형태로 (예: 광주학동오피스텔, 남광주역 오피스텔, 광주 복층 오피스텔 분양)\n"
                + "2. 붙여쓰기/띄어쓰기 변형 중 실제 많이 쓰는 형태 포함 (예: 광주학동오피스텔 / 광주 학동 오피스텔)\n"
                + "3. 단계 구분: 대표(검색량 많고 경쟁 높음) / 중간 / 롱테일(경쟁 낮아 상위노출 쉬움)\n"
                + "4. 지역명(동/구/역) + 상품(오피스텔/도시형생활주택/상가/복층/테라스) + 의도(분양가/모델하우스/평면도/투자/월세/입주) 조합\n"
                + "5. 현장명 브랜드 키워드, 인근 역세권 키워드, 개발호재 연계 키워드, 정책/대출 연계 키워드를 골고루\n"
                + "6. 키워드는 20~30개, 같은 의미 중복 없이\n\n"
                + "【출력 형식 - 반드시 아래 형식 그대로】\n"
                + "[현장 핵심 요약]\n(블로그에 쓸 수 있는 핵심 셀링포인트 7~10개를 한 줄씩. 내부 판매현황 제외)\n\n"
                + "[키워드 전략]\n(대표/중간/롱테일 키워드를 어떤 순서로 포스팅하면 좋은지, 발행 순서 추천 포함)\n\n"
                + "[연재 포스팅 기획 10편]\n1. (메인키워드) - (제목안) - (핵심 내용 한 줄)\n... 10편까지\n\n"
                + "[주의할 점]\n(이 현장 홍보 글에서 피해야 할 표현이나 과장 위험 포인트)\n\n"
                + "===KEYWORDS_START===\n"
                + "키워드|단계|추천글주제|제목안|서브키워드(쉼표구분)\n"
                + "(한 줄에 키워드 하나씩, 위 형식대로 | 로 구분. 단계는 대표/중간/롱테일 중 하나. "
                + "추천글주제는 다음 중 하나: 종합소개, 입지교통, 평면타입, 투자임대, 개발호재, 정책대출, 상가분양, 생활인프라)\n"
                + "===KEYWORDS_END===\n";
    }

    // ─────────────────────────────────────────
    // 포스팅 생성 프롬프트
    // ─────────────────────────────────────────
    private String buildPostPrompt(String keyword, String subKeywords, String angle, String projectName,
                                   String contact, String tone, String memo, String material,
                                   String prevPost, String seoFeedback) {
        String revise = "";
        if (seoFeedback != null && !seoFeedback.isBlank() && prevPost != null && !prevPost.isBlank()) {
            revise = "【이번 작업은 '수정본' 작성이다】\n"
                    + "아래 [이전 글]을 [노출 점검 결과]의 지적사항을 모두 반영해서 다시 써라. "
                    + "좋다고 평가된 부분은 유지하고, 감점 항목과 '고쳐야 할 점'을 우선 해결한다.\n\n"
                    + "[이전 글]\n" + prevPost + "\n\n"
                    + "[노출 점검 결과]\n" + seoFeedback + "\n\n";
        }
        return "너는 네이버 블로그 SEO 전문가이자 10년 경력의 부동산 분양 전문 블로거야.\n"
                + "아래 분양 자료를 바탕으로 ① 포스팅 가이드라인과 ② 실제 네이버 블로그 포스팅 완성본을 작성해줘.\n\n"
                + "【메인 키워드】" + keyword + "\n"
                + "【서브 키워드】" + nz(subKeywords, "자료에 맞게 자동 선정") + "\n"
                + "【이번 글 주제】" + angleGuide(angle) + "\n"
                + "【현장명】" + nz(projectName, "자료에서 파악") + "\n"
                + "【문의/상담 안내】" + nz(contact, "없음 (상담 문의 문구만 자연스럽게)") + "\n"
                + "【말투】" + nz(tone, "친근한 전문가 구어체") + "\n"
                + "【추가 요청】" + nz(memo, "없음") + "\n\n"
                + "【분양 자료 원문 (이 안의 사실만 사용)】\n" + material + "\n\n"
                + COMPLIANCE_RULES + "\n"
                + revise
                + INFO_POST_RULES + "\n"
                + "【포스팅 완성본 작성 규칙】\n"
                + "1. 전체 분량 2000~2500자\n"
                + "2. 제목과 도입부 첫 문장에 메인 키워드를 그대로(띄어쓰기 포함 동일하게) 넣는다\n"
                + "3. 메인 키워드는 본문에 4~6회, 서브 키워드는 각 1~2회 자연스럽게 분산\n"
                + "4. 이번 글 주제에 집중하고, 나머지 정보는 짧게만 언급 (한 글에 모든 내용 몰아넣기 금지 - 연재로 나눔)\n"
                + "5. 면적, 실 수, 층수, 거리(도보 몇 분) 같은 구체적 숫자를 자료에서 정확히 인용\n"
                + "6. 친근한 해요체 (~해요, ~이에요), '~습니다'체 금지, 2~3줄마다 줄바꿈\n"
                + "7. 사진 들어갈 자리는 (사진: 조감도) 처럼 괄호로 표시\n"
                + "8. 마지막에 '본 포스팅은 분양 자료를 바탕으로 작성되었으며, 세부 내용은 사업 진행에 따라 변경될 수 있어요.' 문구 포함\n"
                + "9. [매우 중요] 본문에 하이픈(-), 샵(#), 별표(*) 기호 금지. 맨 마지막 해시태그 줄에서만 # 사용\n"
                + "10. [매우 중요] 이모지/이모티콘 금지\n"
                + "11. [매우 중요] 소제목은 '@@소제목 내용@@' 형태로 3~5개 배치. 소제목 1~2개에는 키워드를 넣는다\n\n"
                + "【출력 형식 - 반드시 아래 구분자 그대로】\n"
                + "===GUIDE_START===\n"
                + "[추천 제목 5개]\n1. \n2. \n3. \n4. \n5. \n\n"
                + "[키워드 배치 가이드]\n▶ 메인 키워드: \n▶ 서브 키워드: \n▶ 롱테일 키워드 3개: \n\n"
                + "[사진 배치 가이드]\n(자료 내 어떤 이미지(조감도/평면도/위치도/아이소 등)를 어느 문단에 넣을지)\n\n"
                + "[다음 연재 추천]\n(이 글 다음에 이어서 쓸 키워드 2개와 주제)\n"
                + "===GUIDE_END===\n"
                + "===POST_START===\n"
                + "(첫 줄은 제목, 그 다음부터 본문. 소제목은 @@ @@, 맨 마지막 줄에 해시태그 10개)\n"
                + "===POST_END===\n";
    }

    // ─────────────────────────────────────────
    // 정보성 글 노출 원칙 (생성/점검 공통 기준)
    // ─────────────────────────────────────────
    private static final String INFO_POST_RULES =
            "【네이버 정보성 글 노출 원칙 - 이 기준으로 쓴다】\n"
                    + "1. 검색 의도 즉답: 도입부 3~4줄 안에 검색한 사람이 가장 궁금한 답(위치, 규모, 구성 등 핵심 숫자)을 먼저 요약한다.\n"
                    + "2. 정보 밀도: 자료의 구체적 숫자(면적㎡/평, 실 수, 층수, 도보 분, 거리 m)를 정확히 인용하고, 뭉뚱그린 칭찬 문장은 쓰지 않는다.\n"
                    + "3. 구조화: 소제목마다 하나의 질문에 답하는 구성. 비교가 필요한 정보는 '항목 : 내용' 형태 줄로 정리한다.\n"
                    + "4. 독창성: 자료에 있는 이 현장만의 정보(층고 3.0m, 15층 복층·테라스, 1·2호선 환승 예정 등)를 중심으로, 어디서나 볼 수 있는 일반론은 최소화한다.\n"
                    + "5. 자주 묻는 질문: 글 후반에 @@자주 묻는 질문@@ 소제목을 두고 Q/A 3개 (예: 주차는? 입주 시기는? 대출 되나요?) - 자료에 없는 답은 '상담 시 확인'으로.\n"
                    + "6. 광고성 낮추기: 감탄사·과장·'지금 바로 연락' 같은 영업 문구 금지. 문의 안내는 마지막에 한 번, 담백하게.\n"
                    + "7. 키워드 과다 반복 금지: 같은 문장에 키워드를 두 번 넣지 말고, 유사어(학동 오피스텔, 남광주역 인근 등)로 자연스럽게 분산한다.\n";

    // ─────────────────────────────────────────
    // 노출 점검 프롬프트
    // ─────────────────────────────────────────
    private String buildSeoCheckPrompt(String keyword, String subKeywords, String post) {
        return "너는 네이버 블로그 검색 상위노출을 전문으로 하는 SEO 컨설턴트다.\n"
                + "아래 분양 정보성 블로그 글이 메인 키워드로 네이버 블로그 검색에서 상위 노출되기에 적합한지 엄격하게 점검해라. "
                + "칭찬 위주로 후하게 주지 말고, 실제 상위 노출 정보성 글과 비교한다고 생각하고 채점한다.\n\n"
                + "【메인 키워드】" + keyword + "\n"
                + "【서브 키워드】" + nz(subKeywords, "없음") + "\n\n"
                + "【점검할 글】\n" + post + "\n\n"
                + INFO_POST_RULES + "\n"
                + COMPLIANCE_RULES + "\n"
                + "【채점 항목 (합계 100점)】\n"
                + "1. 제목 (15): 메인 키워드 앞쪽 배치, 클릭하고 싶은 구체성, 30자 내외\n"
                + "2. 키워드 배치 (15): 도입부 첫 문장, 소제목, 본문 분산, 과다 반복 여부 (본문 메인 키워드 등장 횟수를 세서 적어라)\n"
                + "3. 검색 의도 충족 (20): 검색한 사람이 원하는 답을 빨리, 충분히 주는지\n"
                + "4. 정보 밀도·독창성 (20): 구체적 수치, 이 현장만의 정보, 일반론 비중\n"
                + "5. 구조·가독성 (10): 소제목, 문단 길이, Q/A, 분량(공백 제외 글자 수를 추정해 적어라)\n"
                + "6. 광고성·리스크 (20): 과장/단정 표현, 영업 문구, 내부정보 노출 여부 (위반 시 크게 감점)\n\n"
                + "【출력 형식 - 반드시 그대로, 기호 *, # 사용 금지】\n"
                + "[노출 점수] 00\n"
                + "[한줄 판정] (바로 발행 가능 / 수정 후 발행 / 다시 작성 권장 중 하나 + 이유 한 문장)\n\n"
                + "[항목별 점수]\n제목: 00/15 - 이유\n키워드 배치: 00/15 - 이유 (메인 키워드 N회)\n"
                + "검색 의도 충족: 00/20 - 이유\n정보 밀도·독창성: 00/20 - 이유\n"
                + "구조·가독성: 00/10 - 이유 (약 N자)\n광고성·리스크: 00/20 - 이유\n\n"
                + "[꼭 고쳐야 할 점 TOP 3]\n1. \n2. \n3. \n\n"
                + "[문장 수정 제안]\n기존: \n수정: \n(3개까지)\n\n"
                + "[위험 표현]\n(과장·단정·확정 표현이 있으면 그대로 인용, 없으면 '없음')\n\n"
                + "[더 좋은 제목 3개]\n1. \n2. \n3. \n";
    }

    private String angleGuide(String angle) {
        if (angle == null) angle = "";
        switch (angle) {
            case "입지교통":  return "입지·교통 (역까지 거리, 버스, 광역 교통, 향후 노선 계획 중심)";
            case "평면타입":  return "평면·타입 분석 (전용/계약면적, 층별 구성, 층고, 복층·테라스 중심)";
            case "투자임대":  return "투자·임대 관점 (1~2인 가구 수요, 인근 유사상품 월세 시세 비교 - 단정 금지)";
            case "개발호재":  return "주변 개발 계획 (예정/추진 중인 계획임을 명확히)";
            case "정책대출":  return "부동산 정책·대출 (비아파트 대상 정책, 요건과 시행 시기 명시)";
            case "상가분양":  return "근린생활시설(상가) 분양 (층별 구성, 배후 수요, 면적)";
            case "생활인프라": return "생활 인프라 (병원, 문화시설, 공원, 상권, 학교)";
            case "종합소개":
            default:         return "현장 종합 소개 (사업개요, 위치, 구성, 특장점 요약)";
        }
    }

    private String nz(String v, String def) {
        return (v == null || v.trim().isEmpty()) ? def : v.trim();
    }

    // ─────────────────────────────────────────
    // Gemini API 호출 → GeminiClient 공용
    //  (1번 키 모델 4개 → 2번 키 모델 4개, 한도 초과/혼잡 모델은 잠시 건너뜀)
    // ─────────────────────────────────────────
    @Autowired
    private GeminiClient geminiClient;

    /** 마지막으로 응답에 성공한 모델 (화면 안내용) */
    public String getLastModel() { return geminiClient.getLastModel(); }

    private String callGemini(String prompt) {
        return geminiClient.generate(prompt);
    }
}