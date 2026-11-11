package com.rentjcar.blogai.service;

import com.rentjcar.blogai.account.CurrentUserService;
import com.rentjcar.blogai.dto.EstatePostDto;
import com.rentjcar.blogai.dto.EstateProjectDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Stream;

/**
 * 분양 현장별 데이터 파일 저장소 (DB 없이 폴더/파일로 보관)
 *
 * estate-data/
 *   user-{회원번호}/          ← 회원마다 따로 보관 (다른 회원 자료는 보이지 않음)
 *   {projectId}/
 *     meta.properties     현장명, 지역, 저장일
 *     material.txt        분양 자료 텍스트
 *     keywords.txt        키워드 추천 원문 (AI 응답)
 *     posts/
 *       {postId}.properties  키워드, 주제, 점수, 생성일
 *       {postId}.post.txt    포스팅 본문 원문
 *       {postId}.guide.txt   가이드
 *       {postId}.seo.txt     노출 점검 결과
 */
@Service
public class EstateStorageService {

    @Value("${estate.data-dir:estate-data}")
    private String dataDir;

    private final CurrentUserService currentUser;

    public EstateStorageService(CurrentUserService currentUser) { this.currentUser = currentUser; }

    private static final DateTimeFormatter ID_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final DateTimeFormatter VIEW_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    // ─────────────────────────────────────────
    // 현장(키워드 분석 결과) 저장/조회
    // ─────────────────────────────────────────
    public String saveProject(String projectName, String region, String material, String keywordRaw) {
        String id = LocalDateTime.now().format(ID_FMT);
        Path dir = projectDir(id);
        try {
            Files.createDirectories(dir.resolve("posts"));
            Properties p = new Properties();
            p.setProperty("projectName", nv(projectName));
            p.setProperty("region", nv(region));
            p.setProperty("createdAt", LocalDateTime.now().format(VIEW_FMT));
            writeProps(dir.resolve("meta.properties"), p);
            writeText(dir.resolve("material.txt"), material);
            writeText(dir.resolve("keywords.txt"), keywordRaw);
            return id;
        } catch (IOException e) {
            throw new RuntimeException("SAVE_FAILED");
        }
    }

    /** 키워드를 다시 뽑았을 때 기존 현장에 덮어쓰기 */
    public void updateKeywords(String id, String keywordRaw) {
        if (!exists(id)) return;
        try {
            writeText(projectDir(id).resolve("keywords.txt"), keywordRaw);
        } catch (IOException ignored) {}
    }

    public List<EstateProjectDto> listProjects() {
        List<EstateProjectDto> list = new ArrayList<>();
        Path root = userRoot();
        if (!Files.isDirectory(root)) return list;
        try (Stream<Path> s = Files.list(root)) {
            s.filter(Files::isDirectory)
                    .map(d -> d.getFileName().toString())
                    .filter(this::validId)
                    .sorted(Comparator.reverseOrder())
                    .forEach(id -> {
                        EstateProjectDto dto = loadProjectMeta(id);
                        if (dto != null) list.add(dto);
                    });
        } catch (IOException ignored) {}
        return list;
    }

    public EstateProjectDto loadProjectMeta(String id) {
        if (!exists(id)) return null;
        Properties p = readProps(projectDir(id).resolve("meta.properties"));
        EstateProjectDto dto = new EstateProjectDto();
        dto.setId(id);
        dto.setProjectName(p.getProperty("projectName", ""));
        dto.setRegion(p.getProperty("region", ""));
        dto.setCreatedAt(p.getProperty("createdAt", ""));
        dto.setPostCount(countPosts(id));
        return dto;
    }

    public String loadMaterial(String id) { return exists(id) ? readText(projectDir(id).resolve("material.txt")) : ""; }
    public String loadKeywordRaw(String id) { return exists(id) ? readText(projectDir(id).resolve("keywords.txt")) : ""; }

    public void deleteProject(String id) {
        if (!exists(id)) return;
        try (Stream<Path> walk = Files.walk(projectDir(id))) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
    }

    // ─────────────────────────────────────────
    // 생성한 글 저장/조회
    // ─────────────────────────────────────────
    public String savePost(String projectId, String keyword, String angle, String guide, String post) {
        if (!exists(projectId)) return null;
        String postId = LocalDateTime.now().format(ID_FMT);
        Path dir = projectDir(projectId).resolve("posts");
        try {
            Files.createDirectories(dir);
            Properties p = new Properties();
            p.setProperty("keyword", nv(keyword));
            p.setProperty("angle", nv(angle));
            p.setProperty("title", firstLine(post));
            p.setProperty("createdAt", LocalDateTime.now().format(VIEW_FMT));
            writeProps(dir.resolve(postId + ".properties"), p);
            writeText(dir.resolve(postId + ".post.txt"), post);
            writeText(dir.resolve(postId + ".guide.txt"), guide);
            return postId;
        } catch (IOException e) {
            return null;
        }
    }

    public void saveSeoResult(String projectId, String postId, String seoResult, int score) {
        if (!exists(projectId) || !validId(postId)) return;
        Path dir = projectDir(projectId).resolve("posts");
        Path meta = dir.resolve(postId + ".properties");
        if (!Files.exists(meta)) return;
        try {
            writeText(dir.resolve(postId + ".seo.txt"), seoResult);
            Properties p = readProps(meta);
            p.setProperty("score", String.valueOf(score));
            writeProps(meta, p);
        } catch (IOException ignored) {}
    }

    public List<EstatePostDto> listPosts(String projectId) {
        List<EstatePostDto> list = new ArrayList<>();
        if (!exists(projectId)) return list;
        Path dir = projectDir(projectId).resolve("posts");
        if (!Files.isDirectory(dir)) return list;
        try (Stream<Path> s = Files.list(dir)) {
            s.map(f -> f.getFileName().toString())
                    .filter(n -> n.endsWith(".properties"))
                    .map(n -> n.substring(0, n.length() - ".properties".length()))
                    .filter(this::validId)
                    .sorted(Comparator.reverseOrder())
                    .forEach(id -> list.add(loadPostMeta(projectId, id)));
        } catch (IOException ignored) {}
        return list;
    }

    public EstatePostDto loadPost(String projectId, String postId) {
        if (!exists(projectId) || !validId(postId)) return null;
        Path dir = projectDir(projectId).resolve("posts");
        if (!Files.exists(dir.resolve(postId + ".properties"))) return null;
        EstatePostDto dto = loadPostMeta(projectId, postId);
        dto.setPost(readText(dir.resolve(postId + ".post.txt")));
        dto.setGuide(readText(dir.resolve(postId + ".guide.txt")));
        dto.setSeoResult(readText(dir.resolve(postId + ".seo.txt")));
        return dto;
    }

    public void deletePost(String projectId, String postId) {
        if (!exists(projectId) || !validId(postId)) return;
        Path dir = projectDir(projectId).resolve("posts");
        for (String ext : new String[]{".properties", ".post.txt", ".guide.txt", ".seo.txt"}) {
            try { Files.deleteIfExists(dir.resolve(postId + ext)); } catch (IOException ignored) {}
        }
    }

    private EstatePostDto loadPostMeta(String projectId, String postId) {
        Properties p = readProps(projectDir(projectId).resolve("posts").resolve(postId + ".properties"));
        EstatePostDto dto = new EstatePostDto();
        dto.setId(postId);
        dto.setKeyword(p.getProperty("keyword", ""));
        dto.setAngle(p.getProperty("angle", ""));
        dto.setTitle(p.getProperty("title", ""));
        dto.setCreatedAt(p.getProperty("createdAt", ""));
        dto.setScore(p.getProperty("score", ""));
        return dto;
    }

    // ─────────────────────────────────────────
    // 파일 유틸
    // ─────────────────────────────────────────
    /** 숫자로만 된 ID만 허용 (경로 조작 방지) */
    private boolean validId(String id) { return id != null && id.matches("\\d{8,20}"); }
    private boolean exists(String id)  { return validId(id) && Files.isDirectory(projectDir(id)); }
    private Path projectDir(String id) { return userRoot().resolve(id); }

    /** 로그인한 회원의 폴더 */
    private Path userRoot() {
        return Paths.get(dataDir, "user-" + currentUser.require().getId());
    }

    /**
     * 회원 기능 도입 전에 estate-data/ 바로 아래 저장된 현장들을 관리자 폴더로 옮김
     * @return 옮긴 현장 수
     */
    public int migrateLegacyTo(Long adminId) {
        Path root = Paths.get(dataDir);
        if (!Files.isDirectory(root)) return 0;
        Path target = root.resolve("user-" + adminId);
        int moved = 0;
        try (Stream<Path> s = Files.list(root)) {
            for (Path d : s.filter(Files::isDirectory).toList()) {
                String name = d.getFileName().toString();
                if (!validId(name)) continue;
                Files.createDirectories(target);
                if (Files.exists(target.resolve(name))) continue;
                Files.move(d, target.resolve(name));
                moved++;
            }
        } catch (IOException ignored) {}
        return moved;
    }

    private int countPosts(String id) {
        Path dir = projectDir(id).resolve("posts");
        if (!Files.isDirectory(dir)) return 0;
        try (Stream<Path> s = Files.list(dir)) {
            return (int) s.filter(f -> f.getFileName().toString().endsWith(".properties")).count();
        } catch (IOException e) { return 0; }
    }

    private String firstLine(String text) {
        if (text == null) return "";
        for (String l : text.split("\n")) {
            String t = l.replace("@@", "").trim();
            if (!t.isEmpty()) return t.length() > 80 ? t.substring(0, 80) : t;
        }
        return "";
    }

    private String nv(String v) { return v == null ? "" : v.trim(); }

    private void writeText(Path f, String text) throws IOException {
        Files.writeString(f, text == null ? "" : text, StandardCharsets.UTF_8);
    }

    private String readText(Path f) {
        try { return Files.exists(f) ? Files.readString(f, StandardCharsets.UTF_8) : ""; }
        catch (IOException e) { return ""; }
    }

    private void writeProps(Path f, Properties p) throws IOException {
        try (Writer w = Files.newBufferedWriter(f, StandardCharsets.UTF_8)) { p.store(w, null); }
    }

    private Properties readProps(Path f) {
        Properties p = new Properties();
        if (!Files.exists(f)) return p;
        try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) { p.load(r); }
        catch (IOException ignored) {}
        return p;
    }
}