package com.rentjcar.blogai.account;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.security.web.session.HttpSessionEventPublisher;

import java.io.IOException;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, AppUserRepository users) throws Exception {
        http
            .authorizeHttpRequests(a -> a
                .requestMatchers("/login", "/signup", "/error", "/favicon.ico").permitAll()
                .requestMatchers("/admin", "/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated())
            .formLogin(f -> f
                .loginPage("/login")
                .failureUrl("/login?error")
                .successHandler(new LoginSuccessHandler(users))
                .permitAll())
            .logout(l -> l
                .logoutUrl("/logout")
                .logoutSuccessUrl("/login?logout")
                .permitAll())
            // 한 계정은 한 곳에서만 로그인 (계정 돌려쓰기 방지: 새로 로그인하면 이전 접속은 끊김)
            .sessionManagement(s -> s
                .maximumSessions(1)
                .expiredUrl("/login?kicked"));
        return http.build();
    }

    @Bean
    public UserDetailsService userDetailsService(AppUserRepository users) {
        return username -> users.findByUsername(username)
                .map(u -> User.withUsername(u.getUsername())
                        .password(u.getPasswordHash())
                        .roles(u.isAdmin() ? new String[]{"ADMIN", "USER"} : new String[]{"USER"})
                        .build())
                .orElseThrow(() -> new UsernameNotFoundException(username));
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }

    /** IP 제한 필터: 로그인보다 먼저 실행 */
    @Bean
    public FilterRegistrationBean<IpAllowFilter> ipAllowFilter(IpAccessService ipAccess) {
        FilterRegistrationBean<IpAllowFilter> reg = new FilterRegistrationBean<>(new IpAllowFilter(ipAccess));
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
        reg.addUrlPatterns("/*");
        return reg;
    }

    /** 로그인 성공 시 마지막 접속 시각/IP 기록 */
    static class LoginSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {
        private final AppUserRepository users;
        LoginSuccessHandler(AppUserRepository users) {
            super("/");
            setAlwaysUseDefaultTargetUrl(true);
            this.users = users;
        }
        @Override
        public void onAuthenticationSuccess(HttpServletRequest req, HttpServletResponse res, Authentication auth)
                throws IOException, jakarta.servlet.ServletException {
            users.findByUsername(auth.getName()).ifPresent(u -> {
                u.setLastLoginAt(Times.now());
                u.setLastLoginIp(req.getRemoteAddr());
                users.save(u);
            });
            super.onAuthenticationSuccess(req, res, auth);
        }
    }
}
