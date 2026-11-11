package com.rentjcar.blogai.controller;

import com.rentjcar.blogai.service.BlogSeoService;
import com.rentjcar.blogai.service.UsageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class BlogSeoController {

    @Autowired
    private BlogSeoService blogSeoService;

    @Autowired
    private UsageService usageService;

    @GetMapping("/seo")
    public String seoForm(Model model) {
        addUsageInfo(model);
        return "seo";
    }

    // ─────────────────────────────────────────
    // SEO 분석 (1~5회 반복 후 최고 점수 채택)
    // ─────────────────────────────────────────
    @PostMapping("/seo/analyze")
    public String analyze(
            @RequestParam("keyword") String keyword,
            @RequestParam("title") String title,
            @RequestParam("content") String content,
            @RequestParam(value = "rounds", defaultValue = "1") int rounds,
            Model model) {

        int safeRounds = Math.max(1, Math.min(rounds, 5));

        if (usageService.getRemainingCount() < safeRounds) {
            model.addAttribute("errorMsg",
                    "❌ 오늘의 사용 한도(" + usageService.getDailyLimit() + "회)가 부족해요. " +
                            "선택하신 " + safeRounds + "회 분석에는 " + safeRounds + "회가 필요합니다. " +
                            "(현재 남은 횟수: " + usageService.getRemainingCount() + "회)");
            model.addAttribute("keyword", keyword);
            model.addAttribute("title", title);
            model.addAttribute("content", content);
            model.addAttribute("rounds", safeRounds);
            model.addAttribute("activeTab", "analyze");
            addUsageInfo(model);
            return "seo";
        }
        try {
            String result = blogSeoService.analyzeBest(keyword, title, content, safeRounds);
            for (int i = 0; i < safeRounds; i++) {
                usageService.increaseCount();
            }
            model.addAttribute("analyzeResult", result);
            model.addAttribute("keyword", keyword);
            model.addAttribute("title", title);
            model.addAttribute("content", content);
            model.addAttribute("rounds", safeRounds);
            model.addAttribute("activeTab", "analyze");
            addUsageInfo(model);
            return "seo";
        } catch (RuntimeException e) {
            model.addAttribute("errorMsg", resolveErrorMsg(e.getMessage()));
            model.addAttribute("keyword", keyword);
            model.addAttribute("title", title);
            model.addAttribute("content", content);
            model.addAttribute("rounds", safeRounds);
            model.addAttribute("activeTab", "analyze");
            addUsageInfo(model);
            return "seo";
        }
    }

    // ─────────────────────────────────────────
    // 포스팅 가이드라인 + 완성본 생성 (맛집/여행/숙소/체험/일상 등 다중 카테고리)
    // ─────────────────────────────────────────
    @PostMapping("/seo/guide")
    public String guide(
            @RequestParam("guideKeyword")   String keyword,
            @RequestParam(value = "postType", defaultValue = "맛집") String postType,
            @RequestParam(value = "postTypeOther", required = false, defaultValue = "") String postTypeOther,
            @RequestParam("restaurantName") String name,
            @RequestParam("address")        String address,
            @RequestParam("phone")          String phone,
            @RequestParam("hours")          String hours,
            @RequestParam("menu")           String menu,
            @RequestParam("myReview")       String myReview,
            Model model) {

        if (!usageService.canUse()) {
            model.addAttribute("errorMsg", "❌ 오늘의 사용 한도(" + usageService.getDailyLimit() + "회)를 모두 사용했습니다.");
            model.addAttribute("myReview", myReview);
            model.addAttribute("postType", postType);
            model.addAttribute("postTypeOther", postTypeOther);
            addUsageInfo(model);
            return "seo";
        }
        try {
            String raw = blogSeoService.generateGuideAndPost(
                    keyword, postType, postTypeOther, name, address, phone, hours, menu, myReview);
            usageService.increaseCount();

            // 가이드 / 포스팅 파싱
            String guide = "";
            String post  = "";
            int gs = raw.indexOf("===GUIDE_START===");
            int ge = raw.indexOf("===GUIDE_END===");
            int ps = raw.indexOf("===POST_START===");
            int pe = raw.indexOf("===POST_END===");
            if (gs >= 0 && ge > gs) guide = raw.substring(gs + "===GUIDE_START===".length(), ge).trim();
            if (ps >= 0 && pe > ps) post  = raw.substring(ps + "===POST_START===".length(), pe).trim();

            // 소제목 마커 -> 굵은 글씨(19px) HTML 변환 + 금지 기호/이모지 제거
            String postHtml  = blogSeoService.formatPostAsHtml(post);
            String postPlain = blogSeoService.formatPostAsPlain(post);

            model.addAttribute("guideResult",     guide);
            model.addAttribute("postResultHtml",  postHtml);
            model.addAttribute("postResultPlain", postPlain);
            model.addAttribute("guideKeyword",    keyword);
            model.addAttribute("postType",        postType);
            model.addAttribute("postTypeOther",   postTypeOther);
            model.addAttribute("restaurantName",  name);
            model.addAttribute("address",         address);
            model.addAttribute("phone",           phone);
            model.addAttribute("hours",           hours);
            model.addAttribute("menu",            menu);
            model.addAttribute("activeTab", "guide");
            addUsageInfo(model);
            return "seo";
        } catch (RuntimeException e) {
            model.addAttribute("errorMsg", resolveErrorMsg(e.getMessage()));
            model.addAttribute("postType", postType);
            model.addAttribute("postTypeOther", postTypeOther);
            model.addAttribute("activeTab", "guide");
            addUsageInfo(model);
            return "seo";
        }
    }

    private void addUsageInfo(Model model) {
        model.addAttribute("remainingCount", usageService.getRemainingCount());
        model.addAttribute("usedCount",      usageService.getUsedCount());
        model.addAttribute("dailyLimit",     usageService.getDailyLimit());
    }

    private String resolveErrorMsg(String msg) {
        if ("QUOTA_EXCEEDED".equals(msg))   return "❌ Gemini API 사용 한도 초과! 잠시 후 다시 시도해주세요.";
        if ("PAYMENT_REQUIRED".equals(msg)) return "❌ API 결제 확인이 필요합니다.";
        if ("FORBIDDEN".equals(msg))        return "❌ API 키를 확인해주세요.";
        if ("BUSY".equals(msg))             return "❌ Gemini 서버가 지금 붐벼요. 예비 모델까지 모두 시도했지만 실패했어요. 1~2분 뒤 다시 눌러주세요. (횟수는 차감되지 않았어요)";
        if ("NETWORK".equals(msg))          return "❌ Gemini 서버에 연결하지 못했어요. 인터넷 연결을 확인하고 다시 시도해주세요.";
        return "❌ 오류 발생: " + msg;
    }
}