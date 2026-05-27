package com.salonreview.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Phase-1 auth: a single shared owner login over HTTP Basic, stateless. Everything under
 * {@code /api/**} requires the credential; the actuator health probe stays open for Docker.
 *
 * <p>Credentials come from {@code APP_OWNER_USERNAME}/{@code APP_OWNER_PASSWORD} (env). The browser
 * never sends this directly — the Next.js frontend holds it server-side and proxies calls. Per-user
 * accounts and roles (owner/manager/provider) are deferred to Phase 2 (see docs/ROADMAP.md).
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults());
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UserDetailsService userDetailsService(
            @Value("${app.auth.username:owner}") String username,
            @Value("${app.auth.password:}") String password,
            PasswordEncoder encoder) {
        UserDetails owner = User.withUsername(username)
                .password(encoder.encode(password))
                .roles("OWNER")
                .build();
        return new InMemoryUserDetailsManager(owner);
    }
}
