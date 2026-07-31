package com.salonreview.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salonreview.domain.SmsMessage;
import com.salonreview.marketing.MarketingContactsService;
import com.salonreview.repo.SmsMessageRepository.ConversationSummaryProjection;
import com.salonreview.sms.SmsMessageLogService;
import com.salonreview.sms.TwilioSmsService;
import com.salonreview.web.dto.MarketingContactDto.Contact;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Manager conversation view endpoints (conversations list, thread, manual reply) — MANAGER access
 * to this whole controller is granted in SecurityConfig, not re-tested here (no existing
 * security-integration test convention in this codebase for that layer — verified manually via a
 * real MANAGER-role login, see openspec/changes/lead-followup-and-manager-inbox tasks.md 7.3).
 */
class SmsActivityControllerTest {

    private static final String PHONE = "+15551234567";

    private SmsMessageLogService service;
    private TwilioSmsService smsService;
    private MarketingContactsService contactsService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(SmsMessageLogService.class);
        smsService = mock(TwilioSmsService.class);
        contactsService = mock(MarketingContactsService.class);
        mvc = MockMvcBuilders.standaloneSetup(new SmsActivityController(service, smsService, contactsService)).build();
    }

    private static Contact contact(String givenName, String emailAddress) {
        // id, givenName, familyName, phoneNumber, emailAddress, originalTrafficSource,
        // marketingTrafficSource, channel, utmSource, utmMedium, utmCampaign, landingPageSlug,
        // variantName, deviceType, osName, osVersion, browserName, browserVersion,
        // smsMarketingConsent, emailMarketingConsent, squareProfileUrl, submissions,
        // appointments, createdAt, updatedAt
        return new Contact("id-1", givenName, null, PHONE, emailAddress,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null,
                List.of(), List.of(), Instant.now(), Instant.now());
    }

    private record FakeConversationSummary(String phoneNumber, Instant lastMessageAt, String lastMessageBody,
                                            String lastMessageDirection, Long unreadCount) implements ConversationSummaryProjection {
        @Override public String getPhoneNumber() { return phoneNumber; }
        @Override public Instant getLastMessageAt() { return lastMessageAt; }
        @Override public String getLastMessageBody() { return lastMessageBody; }
        @Override public String getLastMessageDirection() { return lastMessageDirection; }
        @Override public Long getUnreadCount() { return unreadCount; }
    }

    @Test
    @DisplayName("GET /conversations maps each projection row to a ConversationDto")
    void conversationsMapsProjections() throws Exception {
        when(service.conversations()).thenReturn(List.of(
                new FakeConversationSummary(PHONE, Instant.now(), "hi", "INBOUND", 2L)));
        when(contactsService.resolveDisplayNames(List.of(PHONE)))
                .thenReturn(Map.of(PHONE, new MarketingContactsService.ContactNameInfo(
                        "Jane", "Doe", true, "https://app.squareup.com/dashboard/customers/directory/customer/cust-1")));

        mvc.perform(get("/api/owner/automations/activity/conversations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].phoneNumber").value(PHONE))
                .andExpect(jsonPath("$[0].unreadCount").value(2))
                .andExpect(jsonPath("$[0].givenName").value("Jane"))
                .andExpect(jsonPath("$[0].familyName").value("Doe"))
                .andExpect(jsonPath("$[0].smsConsent").value(true))
                .andExpect(jsonPath("$[0].squareProfileUrl").value("https://app.squareup.com/dashboard/customers/directory/customer/cust-1"));
    }

    @Test
    @DisplayName("GET /conversations/{phoneNumber} returns that number's full thread")
    void threadReturnsMessagesForPhone() throws Exception {
        when(service.thread(PHONE)).thenReturn(List.of(
                SmsMessage.builder().id(1L).direction("OUTBOUND").phoneNumber(PHONE).body("hi").status("SENT").build()));

        mvc.perform(get("/api/owner/automations/activity/conversations/{phoneNumber}", PHONE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].body").value("hi"));
    }

    @Test
    @DisplayName("GET /conversations/{phoneNumber}/contact returns the resolved marketing profile")
    void contactReturnsResolvedProfile() throws Exception {
        when(contactsService.contactByPhone(PHONE)).thenReturn(Optional.of(contact("Jane", "jane@example.com")));

        mvc.perform(get("/api/owner/automations/activity/conversations/{phoneNumber}/contact", PHONE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.givenName").value("Jane"))
                .andExpect(jsonPath("$.emailAddress").value("jane@example.com"));
    }

    @Test
    @DisplayName("GET /conversations/{phoneNumber}/contact returns a null body when no profile is found")
    void contactReturnsNullWhenNotFound() throws Exception {
        when(contactsService.contactByPhone(PHONE)).thenReturn(Optional.empty());

        mvc.perform(get("/api/owner/automations/activity/conversations/{phoneNumber}/contact", PHONE))
                .andExpect(status().isOk())
                .andExpect(content().string("null"));
    }

    @Test
    @DisplayName("POST /reply sends via TwilioSmsService.sendManual and returns the result")
    void replySendsManualMessage() throws Exception {
        when(smsService.sendManual(PHONE, "hand-typed reply"))
                .thenReturn(new TwilioSmsService.SmsSendResult(true, null));
        String body = new ObjectMapper().writeValueAsString(
                new SmsActivityController.ReplyRequest(PHONE, "hand-typed reply"));

        mvc.perform(post("/api/owner/automations/activity/reply")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sent").value(true));

        verify(smsService).sendManual(PHONE, "hand-typed reply");
    }
}
