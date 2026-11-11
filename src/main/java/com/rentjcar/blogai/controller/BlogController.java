package com.rentjcar.blogai.controller;

import com.rentjcar.blogai.dto.BlogRequestDto;
import com.rentjcar.blogai.dto.ParsedCarDto;
import com.rentjcar.blogai.service.BlogGeneratorService;
import com.rentjcar.blogai.service.UsageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class BlogController {

    @Autowired
    private BlogGeneratorService blogGeneratorService;

    @Autowired
    private UsageService usageService;

    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("blogRequest", new BlogRequestDto());
        addUsageInfo(model);
        return "index";
    }

    // ─────────────────────────────────────────
    // 모드 1: 폼 입력 방식
    // ─────────────────────────────────────────
    @PostMapping("/generate")
    public String generate(@ModelAttribute BlogRequestDto dto, Model model) {
        if (!usageService.canUse()) {
            model.addAttribute("errorMsg", "❌ 오늘의 사용 한도(" + usageService.getDailyLimit() + "회)를 모두 사용했습니다. 내일 다시 시도해주세요.");
            model.addAttribute("blogRequest", dto);
            addUsageInfo(model);
            return "index";
        }
        try {
            String result = blogGeneratorService.generateBlogPost(dto);
            usageService.increaseCount();
            model.addAttribute("result", result);
            model.addAttribute("dto", dto);
            model.addAttribute("mode", "form");
            addUsageInfo(model);
            return "result";
        } catch (RuntimeException e) {
            model.addAttribute("errorMsg", resolveErrorMsg(e.getMessage()));
            model.addAttribute("blogRequest", dto);
            addUsageInfo(model);
            return "index";
        }
    }

    // ─────────────────────────────────────────
    // 모드 2: 텍스트 붙여넣기 방식
    // API 2회 호출: 블로그 생성 + 견적서 파싱
    // ─────────────────────────────────────────
    @PostMapping("/generate-from-text")
    public String generateFromText(@RequestParam("rawText") String rawText, Model model) {
        if (!usageService.canUse()) {
            model.addAttribute("errorMsg", "❌ 오늘의 사용 한도(" + usageService.getDailyLimit() + "회)를 모두 사용했습니다. 내일 다시 시도해주세요.");
            model.addAttribute("blogRequest", new BlogRequestDto());
            model.addAttribute("returnTab", "text");
            model.addAttribute("rawText", rawText);
            addUsageInfo(model);
            return "index";
        }
        try {
            // 블로그 글 생성
            String result = blogGeneratorService.generateFromRawText(rawText);
            usageService.increaseCount();

            // 견적서용 파싱 (횟수 별도 차감 없이 내부 호출)
            ParsedCarDto parsedDto = blogGeneratorService.parseCarInfo(rawText);

            model.addAttribute("result", result);
            model.addAttribute("rawText", rawText);
            model.addAttribute("parsedDto", parsedDto);
            model.addAttribute("mode", "text");
            addUsageInfo(model);
            return "result";

        } catch (RuntimeException e) {
            model.addAttribute("errorMsg", resolveErrorMsg(e.getMessage()));
            model.addAttribute("blogRequest", new BlogRequestDto());
            model.addAttribute("returnTab", "text");
            model.addAttribute("rawText", rawText);
            addUsageInfo(model);
            return "index";
        }
    }

    private void addUsageInfo(Model model) {
        model.addAttribute("remainingCount", usageService.getRemainingCount());
        model.addAttribute("usedCount", usageService.getUsedCount());
        model.addAttribute("dailyLimit", usageService.getDailyLimit());
    }

    private String resolveErrorMsg(String msg) {
        if ("QUOTA_EXCEEDED".equals(msg))   return "❌ 내 Gemini API 키의 사용 한도(분당/일일)를 넘었어요. 잠시 후 다시 시도하거나, 구글 AI 스튜디오에서 한도를 확인해주세요.";
        if ("PAYMENT_REQUIRED".equals(msg)) return "❌ API 결제 확인이 필요합니다.";
        if ("FORBIDDEN".equals(msg))        return "❌ 등록한 Gemini API 키가 막혀 있어요. 상단 '내 정보'에서 키를 확인해주세요.";
        if ("INVALID_KEY".equals(msg))      return "❌ 등록한 Gemini API 키가 올바르지 않아요. 상단 '내 정보'에서 키를 다시 등록해주세요.";
        if ("NO_API_KEY".equals(msg))       return "❌ Gemini API 키가 아직 등록되지 않았어요. 상단 '내 정보'에서 먼저 등록해주세요.";
        if ("KEY_DECRYPT_FAILED".equals(msg)) return "❌ 저장된 API 키를 읽지 못했어요. '내 정보'에서 키를 다시 등록해주세요.";
        if ("BUSY".equals(msg))             return "❌ Gemini 서버가 지금 붐벼요. 예비 모델까지 모두 시도했지만 실패했어요. 1~2분 뒤 다시 눌러주세요. (횟수는 차감되지 않았어요)";
        if ("NETWORK".equals(msg))          return "❌ Gemini 서버에 연결하지 못했어요. 인터넷 연결을 확인하고 다시 시도해주세요.";
        return "❌ 오류 발생: " + msg;
    }
}