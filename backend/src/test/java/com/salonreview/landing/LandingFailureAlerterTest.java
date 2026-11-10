package com.salonreview.landing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.salonreview.telegram.TelegramNotificationService;

class LandingFailureAlerterTest {

    private TelegramNotificationService telegram;
    private LandingBusinessResolver resolver;
    private LandingFailureAlerter alerter;

    @BeforeEach
    void setUp() {
        telegram = mock(TelegramNotificationService.class);
        resolver = mock(LandingBusinessResolver.class);
        when(resolver.resolve("book.pmu-annakara.com"))
                .thenReturn(new LandingBusinessResolver.BusinessContext(2L, "PMU", "America/Los_Angeles"));
        alerter = new LandingFailureAlerter(telegram, resolver);
    }

    private MockHttpServletRequest request(String path) {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", path);
        r.setRequestURI(path);
        r.addHeader("Host", "book.pmu-annakara.com");
        return r;
    }

    @Test
    void sendsAlertToTheRequestsBusinessWithMaskedContact() {
        MockHttpServletRequest r = request("/api/pmu/bookings/service");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(r));
        try {
            LandingFailureAlerter.rememberContact("jane.doe@example.com", "+1 (619) 555-0142");
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
        alerter.report(r, 502, "Unable to create appointment in Square", "INTERNAL_SERVER_ERROR", null);

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(telegram).sendPlainText(eq(2L), text.capture());
        assertThat(text.getValue()).contains("/api/pmu/bookings/service", "502", "INTERNAL_SERVER_ERROR",
                "***@example.com", "ending 0142").doesNotContain("jane", "555");
    }

    @Test
    void sameFailureIsSentOnceWithinTheWindow() {
        MockHttpServletRequest r = request("/api/pmu/bookings/deposit");
        alerter.report(r, 500, "Internal Server Error", null, new IllegalStateException("x"));
        alerter.report(r, 500, "Internal Server Error", null, new IllegalStateException("x"));
        alerter.report(r, 502, "Unable to create appointment in Square", null, null);
        verify(telegram, times(2)).sendPlainText(anyLong(), anyString());
    }

    @Test
    void ignoresEndpointsThatAreNotBookingOrPayment() {
        alerter.report(request("/api/tracking/visit"), 500, "Internal Server Error", null, null);
        verify(telegram, never()).sendPlainText(anyLong(), anyString());
    }

    @Test
    void neverThrowsWhenTelegramFails() {
        when(telegram.sendPlainText(anyLong(), anyString())).thenThrow(new IllegalStateException("down"));
        alerter.report(request("/api/pmu/bookings/service"), 500, "Internal Server Error", null, null);
    }

    @Test
    void adviceAlertsOnUnexpectedExceptionAndServerErrorsButNotOnAlertedOrClientErrors() {
        LandingFailureAlerter mockAlerter = mock(LandingFailureAlerter.class);
        LandingWriteAdvice advice = new LandingWriteAdvice(mockAlerter);
        MockHttpServletRequest r = request("/api/pmu/bookings/service");

        assertThat(advice.failed(new IllegalStateException("boom"), r).getStatusCode().value()).isEqualTo(500);
        advice.landing(new LandingException(502, "Unable to create appointment in Square"), r);
        advice.landing(new LandingException(502, "Unable to process payment", true, null), r);
        advice.landing(new LandingException(402, "Your card was declined."), r);

        verify(mockAlerter).report(eq(r), eq(500), eq("Internal Server Error"), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.any(IllegalStateException.class));
        verify(mockAlerter).report(eq(r), eq(502), eq("Unable to create appointment in Square"),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull());
        verify(mockAlerter, times(2)).report(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt(),
                anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
