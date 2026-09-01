package com.salonreview.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** {@link SessionDiagnosticsFilter} only ever produces an observable effect via its WARN log — no
 * behavior change to the request/response itself — so these tests capture that log directly
 * (Logback's {@code ListAppender}, no new dependency: logback-classic is already transitive via
 * spring-boot-starter). */
class SessionDiagnosticsFilterTest {

    private final SessionDiagnosticsFilter filter = new SessionDiagnosticsFilter();
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(SessionDiagnosticsFilter.class)).addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        ((Logger) LoggerFactory.getLogger(SessionDiagnosticsFilter.class)).detachAppender(appender);
    }

    private List<ILoggingEvent> warnings() {
        return appender.list.stream().filter(e -> e.getLevel().toString().equals("WARN")).toList();
    }

    @Test
    @DisplayName("No JSESSIONID cookie at all — never logs, not even on a 401 (a genuinely unauthenticated "
            + "visitor hitting a protected route isn't a lost session)")
    void noCookieNeverLogs() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/me");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = (rq, rs) -> ((MockHttpServletResponse) rs).setStatus(401);

        filter.doFilter(req, res, chain);

        assertThat(warnings()).isEmpty();
    }

    @Test
    @DisplayName("JSESSIONID cookie present, chain ends 200 — no session was lost, no log")
    void cookiePresentButSuccessfulNeverLogs() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/me");
        req.setCookies(new jakarta.servlet.http.Cookie("JSESSIONID", "ABC123"));
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = (rq, rs) -> ((MockHttpServletResponse) rs).setStatus(200);

        filter.doFilter(req, res, chain);

        assertThat(warnings()).isEmpty();
    }

    @Test
    @DisplayName("JSESSIONID cookie present, chain ends 401, and the container never attached a session — "
            + "logs sessionFound=false (points at the session store)")
    void cookiePresentAnd401WithNoSessionLogsSessionNotFound() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/owner/overview");
        req.setCookies(new jakarta.servlet.http.Cookie("JSESSIONID", "the-real-session-id-value"));
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = (rq, rs) -> ((MockHttpServletResponse) rs).setStatus(401);

        filter.doFilter(req, res, chain);

        List<ILoggingEvent> logs = warnings();
        assertThat(logs).hasSize(1);
        String message = logs.get(0).getFormattedMessage();
        assertThat(message)
                .contains("path=/api/owner/overview")
                .contains("sessionFound=false")
                .doesNotContain("the-real-session-id-value"); // never the raw session id
    }

    @Test
    @DisplayName("JSESSIONID cookie present, chain ends 401, but the container DID attach a session — "
            + "logs sessionFound=true (points at SecurityContext population instead, a different bug)")
    void cookiePresentAnd401WithSessionAttachedLogsSessionFound() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/me");
        req.setCookies(new jakarta.servlet.http.Cookie("JSESSIONID", "ABC123"));
        req.getSession(true); // simulates the container having resolved/attached a session
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = (rq, rs) -> ((MockHttpServletResponse) rs).setStatus(401);

        filter.doFilter(req, res, chain);

        assertThat(warnings()).hasSize(1);
        assertThat(warnings().get(0).getFormattedMessage()).contains("sessionFound=true");
    }

    @Test
    @DisplayName("/api/login itself is excluded — a bad-credentials 401 there is a login attempt that never "
            + "had a session, not a lost one")
    void loginEndpointExcluded() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/login");
        req.setCookies(new jakarta.servlet.http.Cookie("JSESSIONID", "stale-cookie-from-a-prior-session"));
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        doAnswer(inv -> {
            ((MockHttpServletResponse) inv.getArgument(1)).setStatus(401);
            return null;
        }).when(chain).doFilter(any(), any());

        filter.doFilter(req, res, chain);

        assertThat(warnings()).isEmpty();
        verify(chain).doFilter(any(), any());
    }
}
