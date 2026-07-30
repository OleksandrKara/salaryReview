package com.salonreview.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salonreview.domain.SmsMessage;
import com.salonreview.repo.SmsMessageRepository.ConversationSummaryProjection;
import com.salonreview.sms.SmsMessageLogService;
import com.salonreview.sms.TwilioSmsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(SmsMessageLogService.class);
        smsService = mock(TwilioSmsService.class);
        mvc = MockMvcBuilders.standaloneSetup(new SmsActivityController(service, smsService)).build();
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

        mvc.perform(get("/api/owner/automations/activity/conversations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].phoneNumber").value(PHONE))
                .andExpect(jsonPath("$[0].unreadCount").value(2));
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
