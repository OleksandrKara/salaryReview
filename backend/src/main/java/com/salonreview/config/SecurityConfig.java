package com.salonreview.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

/**
 * Phase-2 auth: real per-user accounts ({@code app_user}) with roles, over a server-side session.
 * Login is {@code POST /api/login} (form-encoded username/password); on success the session cookie
 * (JSESSIONID) identifies the caller. The browser never holds this cookie — the Next.js server proxy
 * does, and forwards it. Authorities are {@code ROLE_OWNER/MANAGER/PROVIDER}; fine-grained checks use
 * {@code @PreAuthorize} on the self/feedback controllers.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final ObjectMapper json = new ObjectMapper();

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/info", "/api/login").permitAll()
                        .requestMatchers("/api/users/**", "/api/owner/**", "/api/rag/admin/**").hasRole("OWNER")
                        .requestMatchers("/api/settlements/me/**").hasRole("PROVIDER")
                        // RAG admin (upload/approve/delete/config) is OWNER-only above; asking +
                        // feedback are OWNER+MANAGER. The admin matcher is listed first so it wins.
                        .requestMatchers("/api/rag/**").hasAnyRole("OWNER", "MANAGER")
                        // KB articles: any authenticated role may read (per-article visibility is
                        // enforced in the service via visible_roles); only OWNER+MANAGER may write,
                        // sync, or use AI drafting. The GET matcher is listed first so it wins.
                        .requestMatchers(HttpMethod.GET, "/api/kb-articles/**").authenticated()
                        .requestMatchers("/api/kb-articles/**").hasAnyRole("OWNER", "MANAGER")
                        // SOPs: owner authors/publishes/archives + views the roster and version
                        // history; managers/providers read (audience-filtered in the service) and
                        // acknowledge. Specific matchers first; the catch-all is OWNER-only.
                        .requestMatchers(HttpMethod.GET, "/api/sops/*/acknowledgment-status").hasRole("OWNER")
                        .requestMatchers(HttpMethod.GET, "/api/sops/*/versions").hasRole("OWNER")
                        .requestMatchers(HttpMethod.POST, "/api/sops/*/acknowledge").hasAnyRole("MANAGER", "PROVIDER")
                        .requestMatchers(HttpMethod.GET, "/api/sops/**").authenticated()
                        .requestMatchers("/api/sops/**").hasRole("OWNER")
                        .requestMatchers("/api/settlements/**", "/api/providers/**", "/api/square/**",
                                "/api/pay-periods/**", "/api/prepaid/**", "/api/owner-customers/**", "/api/redos/**",
                                "/api/manual-credits/**", "/api/no-show-fees/**", "/api/suspicious/**")
                                .hasAnyRole("OWNER", "MANAGER")
                        .anyRequest().authenticated())
                .formLogin(form -> form
                        .loginProcessingUrl("/api/login")
                        .successHandler((req, res, authn) -> writeMe(res, authn.getPrincipal()))
                        .failureHandler((req, res, ex) -> res.sendError(HttpServletResponse.SC_UNAUTHORIZED)))
                .logout(out -> out
                        .logoutUrl("/api/logout")
                        .deleteCookies("JSESSIONID")
                        .logoutSuccessHandler((req, res, authn) -> res.setStatus(HttpServletResponse.SC_OK)))
                // API clients want a 401, not a redirect to a login form, when unauthenticated.
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
        return http.build();
    }

    /** Serialize the authenticated principal as {@code {username, role, providerId}} (also used by /api/me). */
    private void writeMe(HttpServletResponse res, Object principal) throws java.io.IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        if (principal instanceof AppUserPrincipal p) {
            body.put("username", p.getUsername());
            body.put("role", p.getRole().name());
            body.put("providerId", p.getProviderId());
        }
        res.setStatus(HttpServletResponse.SC_OK);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(res.getWriter(), body);
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
