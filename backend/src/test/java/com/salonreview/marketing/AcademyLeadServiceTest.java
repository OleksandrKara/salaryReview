package com.salonreview.marketing;

import com.salonreview.telegram.TelegramNotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AcademyLeadServiceTest {

    private static AcademyLeadService.AcademyLead lead(String name, String email, boolean sms) {
        return new AcademyLeadService.AcademyLead(name, "+16195550100", email, "lips-permanent-makeup-basic",
                "Lips Basic Course", "Weekends only", sms, false, "https://pmu-annakara.com/academy/",
                "google", "cpc", "Academy", null);
    }

    @Test
    void alertNamesCourseContactsAndConsent() {
        String text = AcademyLeadService.formatAlert(lead("Jane Doe", "jane@example.com", true));
        assertThat(text).startsWith("🎓 Academy course request")
                .contains("Course: Lips Basic Course", "Name: Jane Doe", "Phone: +16195550100",
                        "Email: jane@example.com", "Message: Weekends only", "OK to text: yes, OK to email: no",
                        "📄 Page: https://pmu-annakara.com/academy/", "📣 Ad: Academy");
    }

    @Test
    void hubspotPropertiesSplitNameAndCarryCourse() {
        var p = AcademyLeadService.hubspotProperties(lead("Jane Mary Doe", "jane@example.com", false));
        assertThat(p).containsEntry("firstname", "Jane").containsEntry("lastname", "Mary Doe")
                .containsEntry("phone", "+16195550100");
        assertThat(p.get("message")).contains("PMU Academy request: Lips Basic Course", "Weekends only");
    }

    @Test
    void leadIsSavedEvenWhenHubSpotFails() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TelegramNotificationService telegram = mock(TelegramNotificationService.class);
        HubSpotFormClient hubspot = mock(HubSpotFormClient.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(42L);
        when(telegram.sendPlainAlert(eq(2L), anyString())).thenReturn(true);
        when(hubspot.submit(eq("jane@example.com"), anyMap(), anyString(), anyString()))
                .thenReturn(new HubSpotFormClient.Result(false, "HTTP 400: bad form"));

        var out = new AcademyLeadService(jdbc, telegram, hubspot).submit(2L, lead("Jane", "jane@example.com", false));

        assertThat(out.id()).isEqualTo(42L);
        assertThat(out.telegramSent()).isTrue();
        assertThat(out.hubspotError()).contains("400");
        verify(jdbc).update(contains("UPDATE academy_lead"), eq(true), eq(false), eq("HTTP 400: bad form"), eq(42L));
    }

    @Test
    void hubspotClientWithoutFormDoesNothing() {
        var r = new HubSpotFormClient("40072325", "").submit("jane@example.com", java.util.Map.of(), null, null);
        assertThat(r.error()).isEqualTo("not_configured");
    }
}
