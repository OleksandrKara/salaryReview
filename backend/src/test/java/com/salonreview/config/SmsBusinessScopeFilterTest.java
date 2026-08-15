package com.salonreview.config;

import com.salonreview.domain.Business;
import com.salonreview.repo.BusinessRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.PrintWriter;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Covers the live-incident fix: sms_message/twilio_sms_config/telegram_config have no business_id,
 * so a second business's OWNER could otherwise read Business A's SMS history and live
 * Twilio/Telegram credentials — see SmsBusinessScopeFilter's own doc comment.
 */
class SmsBusinessScopeFilterTest {

    private static final Business BUSINESS_A = Business.builder().id(1L).name("A").shortCode("akluxnails")
            .timezone("America/Los_Angeles").active(true).build();

    private BusinessRepository businesses() {
        // legacySmsBusiness() is a default method that delegates to findByShortCode() — must call
        // through to the real default, same as BusinessRepositorySoleTest.
        BusinessRepository repo = mock(BusinessRepository.class, org.mockito.Answers.CALLS_REAL_METHODS);
        when(repo.findByShortCode("akluxnails")).thenReturn(Optional.of(BUSINESS_A));
        return repo;
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/owner/automations/activity/conversations",
            "/api/owner/automations",
            "/api/owner/settings/sms",
            "/api/owner/settings/telegram",
    })
    void blocksASecondBusinessFromScopedPaths(String path) throws Exception {
        var context = new CurrentBusinessContext();
        context.set(2L); // AK PMU, not Business A
        var filter = new SmsBusinessScopeFilter(context, businesses());
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getServletPath()).thenReturn(path);
        HttpServletResponse res = mock(HttpServletResponse.class);
        when(res.getWriter()).thenReturn(mock(PrintWriter.class));
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(res).setStatus(HttpServletResponse.SC_FORBIDDEN);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void allowsBusinessAThrough() throws Exception {
        var context = new CurrentBusinessContext();
        context.set(1L); // Business A
        var filter = new SmsBusinessScopeFilter(context, businesses());
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getServletPath()).thenReturn("/api/owner/automations/activity/conversations");
        HttpServletResponse res = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain).doFilter(req, res);
        verify(res, never()).setStatus(HttpServletResponse.SC_FORBIDDEN);
    }

    @Test
    void unrelatedPathsAreUnaffectedRegardlessOfBusiness() throws Exception {
        var context = new CurrentBusinessContext();
        context.set(2L); // AK PMU
        var filter = new SmsBusinessScopeFilter(context, businesses());
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getServletPath()).thenReturn("/api/owner/settings/business");
        HttpServletResponse res = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain).doFilter(req, res);
        verify(res, never()).setStatus(HttpServletResponse.SC_FORBIDDEN);
    }

    @Test
    void unpopulatedContextPassesThroughToNormalAuthHandling() throws Exception {
        var context = new CurrentBusinessContext(); // never set — unauthenticated request
        var filter = new SmsBusinessScopeFilter(context, businesses());
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getServletPath()).thenReturn("/api/owner/automations/activity/conversations");
        HttpServletResponse res = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain).doFilter(req, res);
        assertThat(context.isPopulated()).isFalse();
    }
}
