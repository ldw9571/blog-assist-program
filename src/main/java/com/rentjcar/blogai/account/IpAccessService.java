package com.rentjcar.blogai.account;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 접속 허용 IP 관리
 *  - IP 제한이 꺼져 있으면 누구나 접속 (로그인은 필요)
 *  - 켜져 있으면 허용 목록에 있는 IP(또는 대역)만 접속
 *  - 차단된 IP는 '최근 차단 기록'에 남겨서 관리자가 바로 허용할 수 있게 함
 */
@Service
public class IpAccessService {

    public static final String SETTING_ENABLED = "ip_filter_enabled";

    private final AllowedIpRepository repo;
    private final AppSettingRepository settings;
    private final boolean forceOff;

    private volatile boolean enabled;
    private volatile List<String> allowed = List.of();

    /** ip → 마지막 차단 기록 */
    private final Map<String, BlockedHit> blocked = new ConcurrentHashMap<>();

    public record BlockedHit(String ip, LocalDateTime at, String path, int count) {}

    public IpAccessService(AllowedIpRepository repo, AppSettingRepository settings,
                           @Value("${app.ip-filter.force-off:false}") boolean forceOff) {
        this.repo = repo;
        this.settings = settings;
        this.forceOff = forceOff;
        reload();
    }

    public synchronized void reload() {
        enabled = settings.findById(SETTING_ENABLED).map(s -> "true".equals(s.getVal())).orElse(false);
        allowed = repo.findAll().stream().map(AllowedIp::getIp).toList();
    }

    public boolean isEnabled() { return enabled; }
    public boolean isForceOff() { return forceOff; }

    public synchronized void setEnabled(boolean on) {
        settings.save(new AppSetting(SETTING_ENABLED, String.valueOf(on)));
        reload();
    }

    public boolean isAllowed(String ip) {
        if (forceOff || !enabled) return true;
        if (ip == null) return false;
        for (String rule : allowed) {
            if (matches(rule, ip)) return true;
        }
        return false;
    }

    public void recordBlocked(String ip, String path) {
        blocked.compute(ip, (k, old) -> new BlockedHit(ip, Times.now(), path, old == null ? 1 : old.count() + 1));
        if (blocked.size() > 100) {                     // 오래된 것부터 정리
            blocked.values().stream().min(Comparator.comparing(BlockedHit::at))
                    .ifPresent(h -> blocked.remove(h.ip()));
        }
    }

    public List<BlockedHit> recentBlocked() {
        return blocked.values().stream()
                .filter(h -> !isAllowedIgnoringSwitch(h.ip()))
                .sorted(Comparator.comparing(BlockedHit::at).reversed())
                .limit(30).toList();
    }

    public void add(String ip, String memo) {
        String clean = normalize(ip);
        if (clean == null) throw new IllegalArgumentException("BAD_IP");
        if (!repo.existsByIp(clean)) repo.save(new AllowedIp(clean, memo == null ? "" : memo.trim()));
        blocked.remove(clean);
        reload();
    }

    public void remove(Long id) {
        repo.deleteById(id);
        reload();
    }

    public List<AllowedIp> list() { return repo.findAllByOrderByIdDesc(); }

    private boolean isAllowedIgnoringSwitch(String ip) {
        for (String rule : allowed) if (matches(rule, ip)) return true;
        return false;
    }

    // ─────────────────────────────────────────
    // IP / 대역 비교
    // ─────────────────────────────────────────
    /** 입력값 검사 + 정리. 올바르지 않으면 null */
    public static String normalize(String input) {
        if (input == null) return null;
        String s = input.trim();
        if (s.isEmpty() || s.length() > 64) return null;
        String addr = s, bits = null;
        int slash = s.indexOf('/');
        if (slash >= 0) { addr = s.substring(0, slash); bits = s.substring(slash + 1); }
        byte[] b = parse(addr);
        if (b == null) return null;
        if (bits != null) {
            try {
                int n = Integer.parseInt(bits);
                if (n < 0 || n > b.length * 8) return null;
            } catch (NumberFormatException e) { return null; }
        }
        return s;
    }

    static boolean matches(String rule, String ip) {
        if (rule.equals(ip)) return true;
        byte[] target = parse(ip);
        if (target == null) return false;
        int slash = rule.indexOf('/');
        byte[] base = parse(slash >= 0 ? rule.substring(0, slash) : rule);
        if (base == null || base.length != target.length) return false;
        int bits = slash >= 0 ? Integer.parseInt(rule.substring(slash + 1)) : base.length * 8;
        for (int i = 0; i < base.length && bits > 0; i++, bits -= 8) {
            int mask = bits >= 8 ? 0xFF : (0xFF << (8 - bits)) & 0xFF;
            if ((base[i] & mask) != (target[i] & mask)) return false;
        }
        return true;
    }

    /** DNS 조회 없이 숫자 IP만 해석 */
    private static byte[] parse(String addr) {
        if (addr == null || addr.isEmpty()) return null;
        boolean ipv4 = addr.matches("\\d{1,3}(\\.\\d{1,3}){3}");
        boolean ipv6 = addr.contains(":") && addr.matches("[0-9a-fA-F:.]+");
        if (!ipv4 && !ipv6) return null;
        if (ipv4) {
            for (String p : addr.split("\\.")) if (Integer.parseInt(p) > 255) return null;
        }
        try { return InetAddress.getByName(addr).getAddress(); }
        catch (Exception e) { return null; }
    }
}
