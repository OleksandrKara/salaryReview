package com.salonreview.web;

import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.domain.MailchimpConfig;
import com.salonreview.sms.EmailDomainHealthService;
import com.salonreview.sms.MailchimpConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Standalone {@code MockMvc}. Role gating is enforced by {@code SecurityConfig} and covered
 * transitively, not re-tested here — see {@code TwilioSmsSettingsControllerTest}'s own doc. */
class EmailDomainHealthControllerTest {

    private static final Long BUSINESS_ID = 2L;

    private EmailDomainHealthService healthService;
    private MailchimpConfigService mailchimpConfig;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        healthService = mock(EmailDomainHealthService.class);
        mailchimpConfig = mock(MailchimpConfigService.class);
        CurrentBusinessContext currentBusinessContext = mock(CurrentBusinessContext.class);
        when(currentBusinessContext.id()).thenReturn(BUSINESS_ID);
        EmailDomainHealthController controller = new EmailDomainHealthController(healthService, mailchimpConfig, currentBusinessContext);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("Extracts the domain from mailchimp_config.from_email and delegates to the health service")
    void extractsDomainFromFromEmail() throws Exception {
        when(mailchimpConfig.get(BUSINESS_ID)).thenReturn(
                MailchimpConfig.builder().businessId(BUSINESS_ID).fromEmail("anna@pmu-annakara.com").build());
        when(healthService.check("pmu-annakara.com")).thenReturn(new EmailDomainHealthService.Result(
                "pmu-annakara.com", 85, "Good",
                new EmailDomainHealthService.Check(true, "v=spf1 ..."),
                new EmailDomainHealthService.Check(true, "Found: Google Workspace (google._domainkey)"),
                new EmailDomainHealthService.Check(true, "p=none (published, but not enforced — monitoring only)"),
                new EmailDomainHealthService.Check(true, "1 MX host(s), all resolve: smtp.google.com"),
                Instant.parse("2026-08-31T22:00:00Z")));

        mvc.perform(get("/api/owner/settings/email-domain-health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.domain").value("pmu-annakara.com"))
                .andExpect(jsonPath("$.score").value(85))
                .andExpect(jsonPath("$.rating").value("Good"))
                .andExpect(jsonPath("$.spf.pass").value(true))
                .andExpect(jsonPath("$.dkim.detail").value("Found: Google Workspace (google._domainkey)"));
    }

    @Test
    @DisplayName("No from_email configured — returns configured:false and never touches DNS")
    void noFromEmailConfiguredSkipsDnsEntirely() throws Exception {
        when(mailchimpConfig.get(BUSINESS_ID)).thenReturn(MailchimpConfig.builder().businessId(BUSINESS_ID).build());

        mvc.perform(get("/api/owner/settings/email-domain-health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(false))
                .andExpect(jsonPath("$.domain").doesNotExist());

        verifyNoInteractions(healthService);
    }
}
