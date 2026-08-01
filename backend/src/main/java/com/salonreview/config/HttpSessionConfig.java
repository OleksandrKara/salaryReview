package com.salonreview.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.jdbc.config.annotation.web.http.EnableJdbcHttpSession;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

/**
 * HttpSession is Postgres-backed (spring-session-jdbc), not Tomcat's in-memory default — a backend
 * redeploy no longer signs out every active user, since the session data lives in the same DB the
 * app already depends on. Schema is Flyway-managed (V59), not Spring Session's own auto-init.
 *
 * <p>This has to be wired via {@code @EnableJdbcHttpSession} explicitly, not the usual
 * {@code spring.session.*} properties in application.yml — Spring Boot 4.0.6 ships no
 * auto-configuration for those properties at all (verified: spring-boot-autoconfigure-4.0.6's own
 * configuration metadata has zero "session" entries, and there's no separate spring-boot-session
 * module in this Boot version either). A {@code store-type: jdbc} property with no consumer is
 * exactly how this silently never worked before — it read as correct config while every session
 * was still an ordinary in-memory one, wiped on every restart.
 *
 * <p>{@code maxInactiveIntervalInSeconds} must be a compile-time int (annotation attribute, not a
 * property placeholder) — 30 days, a sliding idle window reset on every authenticated request (see
 * SecurityConfig). Comfortably covers a provider's ~biweekly login cadence, while an actively-used
 * session (a manager's all-shift-open tab, the owner's constant use) never hits the wall. See
 * app/lib/authCookies.ts on the frontend for the matching browser-cookie lifetime — that cookie is
 * refreshed on every proxied request so it slides in lockstep with this server-side timeout.
 */
@Configuration
@EnableJdbcHttpSession(maxInactiveIntervalInSeconds = 60 * 60 * 24 * 30)
public class HttpSessionConfig {

    /**
     * Spring Session's own default session-cookie name is {@code SESSION}, not the servlet
     * container's {@code JSESSIONID} — without this bean, enabling {@code @EnableJdbcHttpSession}
     * would silently switch the cookie name out from under the frontend, which hardcodes
     * {@code JSESSIONID} in three places (app/api/login/route.ts's {@code sessionIdFrom}, and the
     * {@code Cookie: JSESSIONID=...} header built in both proxyBackend.ts and serverApi.ts).
     * Keeping the name unchanged means every one of those call sites, and every session id a
     * browser already holds, keeps working with zero frontend changes.
     */
    @Bean
    CookieSerializer cookieSerializer() {
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();
        serializer.setCookieName("JSESSIONID");
        return serializer;
    }
}
