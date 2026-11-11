package com.rentjcar.blogai.controller;

import com.rentjcar.blogai.dto.EstatePostDto;
import com.rentjcar.blogai.dto.EstateProjectDto;
import com.rentjcar.blogai.service.EstateBlogService;
import com.rentjcar.blogai.service.EstateStorageService;
import com.rentjcar.blogai.service.UsageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

/**
 * 🏢 분양 블로그 도우미 (/estate)
 *  STEP 1: 분양자료 PDF → 키워드 + 연재 기획 추천 → 현장별로 저장
 *  STEP 2: 키워드 선택 → 가이드 + 포스팅 완성본 → (자동) 노출 점검 → 저장
 *  저장된 현장은 AI 호출 없이 다시 불러와서 계속 사용
 */
@Controller
public class EstateBlogController {

    @Autowired private EstateBlogService estateBlogService;
    @Autowired private EstateStorageService storage;
    @Autowired private UsageService usageService;

    // ─────────────────────────────────────────
    // 화면 (저장된 현장/글 불러오기 포함, 횟수 차감 없음)
    // ─────────────────────────────────────────
    @GetMapping("/estate")
    public String form(@RequestParam(value = "project", required = false) String projectId,
                       @RequestParam(value = "post", required = false) String postId,
                       Model model) {
        if (projectId != null && !projectId.isBlank()) {
            if (!loadProject(model, projectId)) {
                model.addAttribute("errorMsg", "❌ 저장된 현장을 찾지 못했어요.");
            } else if (postId != null && !postId.isBlank()) {
                loadPost(model, projectId, postId);
            }
        }
        return render(model);
    }

    // ─────────────────────────────────────────
    // STEP 1: 자료 분석 → 키워드 추천 (+ 저장)
    //  mode=extract  : PDF 텍스트만 추출 (AI 호출 X, 차감 X)
    //  mode=keywords : 키워드 추천까지 (1회 차감)
    // ─────────────────────────────────────────
    @PostMapping("/estate/keywords")
    public String keywords(
            @RequestParam(value = "pdfFile", required = false) MultipartFile pdfFile,
            @RequestParam(value = "material", required = false, defaultValue = "") String material,
            @RequestParam(value = "projectName", required = false, defaultValue = "") String projectName,
            @RequestParam(value = "region", required = false, defaultValue = "") String region,
            @RequestParam(value = "projectId", required = false, defaultValue = "") String projectId,
            @RequestParam(value = "mode", required = false, defaultValue = "keywords") String mode,
            Model model) {

        model.addAttribute("projectName", projectName);
        model.addAttribute("region", region);
        try {
            boolean newPdf = pdfFile != null && !pdfFile.isEmpty();
            if (newPdf) {
                material = estateBlogService.extractPdfText(pdfFile);
                projectId = ""; // 새 PDF면 새 현장으로 저장
                model.addAttribute("infoMsg", "✅ PDF에서 " + String.format("%,d", material.length()) + "자를 추출했어요. ("
                        + pdfFile.getOriginalFilename() + ")");
            } else {
                material = estateBlogService.cleanMaterial(material);
            }
            model.addAttribute("material", material);
            model.addAttribute("projectId", projectId);

            if (material.isBlank()) {
                model.addAttribute("errorMsg", "❌ PDF를 올리거나 분양 자료 내용을 붙여넣어 주세요.");
                return render(model);
            }
            if ("extract".equals(mode)) return render(model);

            if (!usageService.canUse()) {
                model.addAttribute("errorMsg", "❌ 오늘의 사용 한도(" + usageService.getDailyLimit() + "회)를 모두 사용했습니다.");
                return render(model);
            }
            String raw = estateBlogService.recommendKeywords(projectName, region, material);
            usageService.increaseCount();

            if (!projectId.isBlank() && storage.loadProjectMeta(projectId) != null) {
                storage.updateKeywords(projectId, raw);
            } else {
                projectId = storage.saveProject(projectName, region, material, raw);
            }
            loadProject(model, projectId);
            model.addAttribute("infoMsg", "💾 키워드 분석 결과를 저장했어요. 위 '저장된 현장'에서 언제든 다시 불러올 수 있어요.");
            model.addAttribute("activeStep", "keywords");
        } catch (RuntimeException e) {
            model.addAttribute("errorMsg", resolveErrorMsg(e.getMessage()));
        }
        return render(model);
    }

    // ─────────────────────────────────────────
    // STEP 2: 키워드 → 가이드 + 완성본 (+ 자동 노출 점검, 저장)
    //  prevPost + seoFeedback 이 있으면 '점검 반영 수정본'으로 작성
    // ─────────────────────────────────────────
    @PostMapping("/estate/post")
    public String post(
            @RequestParam("keyword") String keyword,
            @RequestParam(value = "subKeywords", required = false, defaultValue = "") String subKeywords,
            @RequestParam(value = "angle", required = false, defaultValue = "종합소개") String angle,
            @RequestParam(value = "projectId", required = false, defaultValue = "") String projectId,
            @RequestParam(value = "projectName", required = false, defaultValue = "") String projectName,
            @RequestParam(value = "region", required = false, defaultValue = "") String region,
            @RequestParam(value = "contact", required = false, defaultValue = "") String contact,
            @RequestParam(value = "tone", required = false, defaultValue = "") String tone,
            @RequestParam(value = "memo", required = false, defaultValue = "") String memo,
            @RequestParam(value = "material", required = false, defaultValue = "") String material,
            @RequestParam(value = "autoCheck", required = false, defaultValue = "false") boolean autoCheck,
            @RequestParam(value = "useFixedContact", required = false, defaultValue = "false") boolean useFixedContact,
            @RequestParam(value = "prevPost", required = false, defaultValue = "") String prevPost,
            @RequestParam(value = "seoFeedback", required = false, defaultValue = "") String seoFeedback,
            Model model) {

        // 저장된 현장이면 자료/키워드는 서버 저장본 사용
        if (!projectId.isBlank() && loadProject(model, projectId)) {
            material = storage.loadMaterial(projectId);
        } else {
            projectId = "";
            model.addAttribute("projectName", projectName);
            model.addAttribute("region", region);
            model.addAttribute("material", material);
        }
        keepPostForm(model, keyword, subKeywords, angle, contact, tone, memo, autoCheck);
        model.addAttribute("useFixedContact", useFixedContact);
        model.addAttribute("activeStep", "post");

        try {
            if (material.isBlank()) {
                model.addAttribute("errorMsg", "❌ 분양 자료가 비어 있어요. STEP 1에서 PDF를 먼저 올려주세요.");
                return render(model);
            }
            if (!usageService.canUse()) {
                model.addAttribute("errorMsg", "❌ 오늘의 사용 한도(" + usageService.getDailyLimit() + "회)를 모두 사용했습니다.");
                return render(model);
            }
            String contactForAi = useFixedContact ? EstateBlogService.FIXED_CONTACT_PROMPT : contact;
            String raw = estateBlogService.generatePost(keyword, subKeywords, angle,
                    (String) model.getAttribute("projectName"), contactForAi, tone, memo, material, prevPost, seoFeedback);
            usageService.increaseCount();

            String guide = between(raw, "===GUIDE_START===", "===GUIDE_END===");
            String post  = between(raw, "===POST_START===", "===POST_END===");
            if (post.isEmpty()) post = raw;
            if (useFixedContact) post = estateBlogService.appendContact(post);

            // 현장이 없으면(자료만 붙여넣고 바로 쓴 경우) 지금 만들어서 저장
            if (projectId.isBlank()) {
                projectId = storage.saveProject((String) model.getAttribute("projectName"),
                        (String) model.getAttribute("region"), material, "");
            }
            String postId = storage.savePost(projectId, keyword, angle, guide, post);

            String seo = "";
            if (autoCheck) {
                if (usageService.canUse()) {
                    seo = estateBlogService.checkSeo(keyword, subKeywords, post);
                    usageService.increaseCount();
                    storage.saveSeoResult(projectId, postId, seo, estateBlogService.extractScore(seo));
                } else {
                    model.addAttribute("infoMsg", "글은 저장했지만 남은 횟수가 없어 노출 점검은 건너뛰었어요.");
                }
            }
            loadProject(model, projectId);
            loadPost(model, projectId, postId);
            if (!seoFeedback.isBlank()) model.addAttribute("infoMsg", "✏️ 점검 결과를 반영한 수정본이에요. 점수를 비교해 보세요.");
        } catch (RuntimeException e) {
            model.addAttribute("errorMsg", resolveErrorMsg(e.getMessage()));
        }
        return render(model);
    }

    // ─────────────────────────────────────────
    // 저장된 글 노출 점검 (다시 점검 / 점검 안 했던 글)
    // ─────────────────────────────────────────
    @PostMapping("/estate/check")
    public String check(@RequestParam("projectId") String projectId,
                        @RequestParam("postId") String postId,
                        @RequestParam(value = "subKeywords", required = false, defaultValue = "") String subKeywords,
                        Model model) {
        if (!loadProject(model, projectId)) return render(model);
        EstatePostDto saved = storage.loadPost(projectId, postId);
        if (saved == null) {
            model.addAttribute("errorMsg", "❌ 저장된 글을 찾지 못했어요.");
            return render(model);
        }
        try {
            if (!usageService.canUse()) {
                model.addAttribute("errorMsg", "❌ 오늘의 사용 한도(" + usageService.getDailyLimit() + "회)를 모두 사용했습니다.");
            } else {
                String seo = estateBlogService.checkSeo(saved.getKeyword(), subKeywords, saved.getPost());
                usageService.increaseCount();
                storage.saveSeoResult(projectId, postId, seo, estateBlogService.extractScore(seo));
            }
        } catch (RuntimeException e) {
            model.addAttribute("errorMsg", resolveErrorMsg(e.getMessage()));
        }
        loadPost(model, projectId, postId);
        model.addAttribute("subKeywords", subKeywords);
        return render(model);
    }

    @PostMapping("/estate/delete")
    public String deleteProject(@RequestParam("projectId") String projectId) {
        storage.deleteProject(projectId);
        return "redirect:/estate";
    }

    @PostMapping("/estate/post/delete")
    public String deletePost(@RequestParam("projectId") String projectId, @RequestParam("postId") String postId) {
        storage.deletePost(projectId, postId);
        return "redirect:/estate?project=" + projectId;
    }

    // ─────────────────────────────────────────
    // 모델 구성 헬퍼
    // ─────────────────────────────────────────
    private boolean loadProject(Model model, String projectId) {
        EstateProjectDto p = storage.loadProjectMeta(projectId);
        if (p == null) return false;
        model.addAttribute("project", p);
        model.addAttribute("projectId", p.getId());
        model.addAttribute("projectName", p.getProjectName());
        model.addAttribute("region", p.getRegion());
        model.addAttribute("material", storage.loadMaterial(projectId));
        String raw = storage.loadKeywordRaw(projectId);
        if (!raw.isBlank()) {
            model.addAttribute("keywordReport", estateBlogService.stripKeywordTable(raw));
            model.addAttribute("keywordList", estateBlogService.parseKeywordTable(raw));
        }
        model.addAttribute("postList", storage.listPosts(projectId));
        return true;
    }

    private void loadPost(Model model, String projectId, String postId) {
        EstatePostDto p = storage.loadPost(projectId, postId);
        if (p == null) return;
        model.addAttribute("currentPost", p);
        model.addAttribute("guideResult", p.getGuide());
        model.addAttribute("postRaw", p.getPost());
        model.addAttribute("postResultHtml", estateBlogService.formatPostAsHtml(p.getPost()));
        model.addAttribute("postResultPlain", estateBlogService.formatPostAsPlain(p.getPost()));
        if (p.getSeoResult() != null && !p.getSeoResult().isBlank()) {
            model.addAttribute("seoResult", p.getSeoResult());
            model.addAttribute("seoScore", estateBlogService.extractScore(p.getSeoResult()));
        }
        if (model.getAttribute("keyword") == null) {
            model.addAttribute("keyword", p.getKeyword());
            model.addAttribute("angle", p.getAngle());
        }
        model.addAttribute("activeStep", "post");
    }

    private void keepPostForm(Model model, String keyword, String subKeywords, String angle,
                              String contact, String tone, String memo, boolean autoCheck) {
        model.addAttribute("keyword", keyword);
        model.addAttribute("subKeywords", subKeywords);
        model.addAttribute("angle", angle);
        model.addAttribute("contact", contact);
        model.addAttribute("tone", tone);
        model.addAttribute("memo", memo);
        model.addAttribute("autoCheck", autoCheck);
    }

    private String render(Model model) {
        model.addAttribute("projectList", storage.listProjects());
        if (model.getAttribute("autoCheck") == null) model.addAttribute("autoCheck", true);
        if (model.getAttribute("useFixedContact") == null) model.addAttribute("useFixedContact", true);
        model.addAttribute("remainingCount", usageService.getRemainingCount());
        model.addAttribute("usedCount", usageService.getUsedCount());
        model.addAttribute("dailyLimit", usageService.getDailyLimit());
        return "estate";
    }

    private String between(String raw, String start, String end) {
        if (raw == null) return "";
        int s = raw.indexOf(start);
        int e = raw.indexOf(end);
        if (s >= 0 && e > s) return raw.substring(s + start.length(), e).trim();
        return "";
    }

    private String resolveErrorMsg(String msg) {
        if ("QUOTA_EXCEEDED".equals(msg))   return "❌ 내 Gemini API 키의 사용 한도(분당/일일)를 넘었어요. 잠시 후 다시 시도하거나, 구글 AI 스튜디오에서 한도를 확인해주세요.";
        if ("PAYMENT_REQUIRED".equals(msg)) return "❌ API 결제 확인이 필요합니다.";
        if ("FORBIDDEN".equals(msg))        return "❌ 등록한 Gemini API 키가 막혀 있어요. 상단 '내 정보'에서 키를 확인해주세요.";
        if ("INVALID_KEY".equals(msg))      return "❌ 등록한 Gemini API 키가 올바르지 않아요. 상단 '내 정보'에서 키를 다시 등록해주세요.";
        if ("NO_API_KEY".equals(msg))       return "❌ Gemini API 키가 아직 등록되지 않았어요. 상단 '내 정보'에서 먼저 등록해주세요.";
        if ("KEY_DECRYPT_FAILED".equals(msg)) return "❌ 저장된 API 키를 읽지 못했어요. '내 정보'에서 키를 다시 등록해주세요.";
        if ("PDF_READ_FAILED".equals(msg))  return "❌ PDF를 읽지 못했어요. 암호가 걸려있거나 손상된 파일인지 확인해주세요.";
        if ("SAVE_FAILED".equals(msg))      return "❌ 서버에 저장하지 못했어요. 폴더 쓰기 권한을 확인해주세요.";
        if ("BUSY".equals(msg))             return "❌ Gemini 서버가 지금 붐벼요. 예비 모델까지 모두 시도했지만 실패했어요. 1~2분 뒤 다시 눌러주세요. (횟수는 차감되지 않았어요)";
        if ("NETWORK".equals(msg))          return "❌ Gemini 서버에 연결하지 못했어요. 인터넷 연결을 확인하고 다시 시도해주세요.";
        if ("EMPTY_RESPONSE".equals(msg))   return "❌ AI가 빈 응답을 보냈어요. 한 번 더 시도해주세요.";
        return "❌ 오류 발생: " + msg;
    }
}