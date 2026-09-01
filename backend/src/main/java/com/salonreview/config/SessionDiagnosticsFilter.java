package com.salonreview.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * Diagnoses a 401 that arrives WITH a JSESSIONID cookie already attached — the "silently logged
 * out" shape reported live on 2026-09-01, ~7 minutes after a deploy (see nginx access.log:
 * 3x {@code GET /api/me} 401 at 00:03:22, forcing a real re-login at 00:04:12 — no exception, no
 * 500, a clean "not authenticated"). {@link AppUserPrincipal} already carries a pinned {@code
 * serialVersionUID} (see its own doc, PR #351) specifically to rule out the classic "recompiled
 * class breaks every existing session's deserialization" cause, and that class is unchanged, so
 * this was something else. Logs enough to tell, next time it happens, exactly <em>where</em> the
 * session was lost:
 *
 * <ul>
 *   <li>{@code sessionFound=false} — Spring Session's JDBC repository (see
 *       {@link HttpSessionConfig}) couldn't find/deserialize the row for this cookie at all (a
 *       DB-read miss, or a transient failure during it) — points at the session store itself.</li>
 *   <li>{@code sessionFound=true} — a session object exists, but Spring Security didn't consider
 *       the caller authenticated from it — points at {@code SecurityContext} population instead,
 *       a different bug entirely.</li>
 * </ul>
 *
 * <p>{@code upSeconds} tests the leading theory directly: a fresh backend instance reporting
 * {@code /actuator/health} healthy before its DB connection pool is fully warmed, so the very
 * first session read after startup fails transiently. A cluster of these logs at a low {@code
 * upSeconds} across a deploy would confirm it; scattered ones at high {@code upSeconds} would rule
 * it out and point elsewhere (a real expiry, a genuine deserialization gap somewhere other than
 * {@code AppUserPrincipal}, etc).
 *
 * <p>Positioned inside the Spring Security filter chain itself (see {@link SecurityConfig}), not
 * registered standalone like {@link RequestTimingFilter} — {@code request.getSession(false)} only
 * reflects Spring Session's JDBC-backed resolution for filters running <em>after</em> Spring
 * Session's own filter in the chain, and that ordering relative to a standalone registration isn't
 * guaranteed the same way Spring Boot's Security+Session integration already is.
 *
 * <p>Deliberately does NOT log the raw session id (it's a live credential, same reasoning as never
 * logging a password) — a short truncated hash is enough to correlate log lines / cross-reference
 * against {@code spring_session} by hand without making the log file itself a usable secret.
 */
public class SessionDiagnosticsFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(SessionDiagnosticsFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String sessionId = cookieValue(request, "JSESSIONID");
        chain.doFilter(request, response);
        if (sessionId == null || response.getStatus() != 401) {
            return;
        }
        boolean sessionFound = request.getSession(false) != null;
        long upSeconds = ManagementFactory.getRuntimeMXBean().getUptime() / 1000;
        log.warn("SESSION 401: path={} sessionFound={} upSeconds={} sessionIdHash={}",
                request.getRequestURI(), sessionFound, upSeconds, shortHash(sessionId));
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // /api/login itself legitimately 401s on bad credentials — that's not a lost session,
        // it's a login attempt that never had one. Excluding it keeps this log grep-able as
        // exclusively "a session that should have worked, didn't."
        return "/api/login".equals(request.getRequestURI());
    }

    private static String cookieValue(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie c : cookies) {
            if (name.equals(c.getName())) return c.getValue();
        }
        return null;
    }

    private static String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest).substring(0, 10);
        } catch (NoSuchAlgorithmException e) {
            return "unknown";
        }
    }
}
