package com.rentjcar.blogai.account;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.HtmlUtils;

import java.io.IOException;

/** 허용되지 않은 IP는 로그인 화면조차 보이지 않게 막음 (가장 먼저 실행) */
public class IpAllowFilter extends OncePerRequestFilter {

    private final IpAccessService ipAccess;

    public IpAllowFilter(IpAccessService ipAccess) { this.ipAccess = ipAccess; }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String ip = req.getRemoteAddr();
        if (ipAccess.isAllowed(ip)) {
            chain.doFilter(req, res);
            return;
        }
        ipAccess.recordBlocked(ip, req.getRequestURI());
        res.setStatus(HttpServletResponse.SC_FORBIDDEN);
        res.setContentType("text/html;charset=UTF-8");
        String safeIp = HtmlUtils.htmlEscape(ip == null ? "-" : ip);
        res.getWriter().write("""
                <!DOCTYPE html><html lang="ko"><head><meta charset="UTF-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>접속이 허용되지 않은 IP</title></head>
                <body style="font-family:'Malgun Gothic',sans-serif;background:#f0f2f5;padding:40px 16px;">
                <div style="max-width:440px;margin:0 auto;background:#fff;border-radius:12px;padding:28px;
                            box-shadow:0 2px 8px rgba(0,0,0,.08);text-align:center;">
                  <div style="font-size:40px;">🔒</div>
                  <h2 style="margin:10px 0 8px;font-size:18px;color:#1a1a2e;">접속이 허용되지 않은 IP예요</h2>
                  <p style="color:#555;font-size:14px;line-height:1.7;">아래 IP 주소를 관리자에게 보내주시면<br>확인 후 접속을 열어드려요.</p>
                  <div style="margin:16px 0;padding:12px;background:#f8f9fa;border-radius:8px;font-size:20px;
                              font-weight:bold;letter-spacing:1px;color:#1d4e89;">%s</div>
                </div></body></html>
                """.formatted(safeIp));
    }
}
