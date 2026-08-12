package com.salonreview.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salonreview.ai.SmsDraftService;
import com.salonreview.config.AppUserPrincipal;
import com.salonreview.domain.AppUser;
import com.salonreview.domain.BlockedNumber;
import com.salonreview.domain.SmsMessage;
import com.salonreview.marketing.MarketingContactsService;
import com.salonreview.repo.AppUserRepository;
import com.salonreview.repo.BlockedNumberRepository;
import com.salonreview.repo.SmsMessageRepository.ConversationSummaryProjection;
import com.salonreview.sms.SmsEventBroadcaster;
import com.salonreview.sms.SmsMediaService;
import com.salonreview.sms.SmsMessageLogService;
import com.salonreview.sms.SmsMessageLogService.ConversationSearchHit;
import com.salonreview.sms.SmsReactionService;
import com.salonreview.sms.TwilioSmsService;
import com.salonreview.web.dto.MarketingContactDto.Contact;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
    private BlockedNumberRepository blockedNumberRepository;
    private SmsEventBroadcaster events;
    private SmsMediaService mediaService;
    private SmsReactionService reactionService;
    private SmsDraftService draftService;
    private AppUserRepository users;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(SmsMessageLogService.class);
        smsService = mock(TwilioSmsService.class);
        contactsService = mock(MarketingContactsService.class);
        blockedNumberRepository = mock(BlockedNumberRepository.class);
        events = mock(SmsEventBroadcaster.class);
        mediaService = mock(SmsMediaService.class);
        reactionService = mock(SmsReactionService.class);
        draftService = mock(SmsDraftService.class);
        users = mock(AppUserRepository.class);
        when(blockedNumberRepository.findByPhoneNumberIn(any())).thenReturn(List.of());
        when(mediaService.mediaForMessages(any())).thenReturn(Map.of());
        when(reactionService.reactionsForMessages(any())).thenReturn(Map.of());
        mvc = MockMvcBuilders.standaloneSetup(
                new SmsActivityController(service, smsService, contactsService, blockedNumberRepository, events, mediaService, reactionService, draftService, users)).build();
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
                List.of(), List.of(), Instant.now(), Instant.now(),
                null, null, null, null,
                false, null);
    }

    private record FakeConversationSummary(String phoneNumber, Instant lastMessageAt, String lastMessageBody,
                                            String lastMessageDirection, Long unreadCount) implements ConversationSummaryProjection {
        @Override public String getPhoneNumber() { return phoneNumber; }
        @Override public Instant getLastMessageAt() { return lastMessageAt; }
        @Override public String getLastMessageBody() { return lastMessageBody; }
        @Override public String getLastMessageDirection() { return lastMessageDirection; }
        @Override public Long getUnreadCount() { return unreadCount; }
        @Override public String getLastMessageDeliveryStatus() { return null; }
        @Override public String getLastMessageDeliveryErrorMessage() { return null; }
        @Override public boolean getHasNegativeFeedback() { return false; }
    }

    @Test
    @DisplayName("GET /conversations maps each projection row to a ConversationDto")
    void conversationsMapsProjections() throws Exception {
        when(service.conversations()).thenReturn(List.of(
                new FakeConversationSummary(PHONE, Instant.now(), "hi", "INBOUND", 2L)));
        when(contactsService.resolveDisplayNames(List.of(PHONE)))
                .thenReturn(Map.of(PHONE, new MarketingContactsService.ContactNameInfo(
                        "Jane", "Doe", true, "https://app.squareup.com/dashboard/customers/directory/customer/cust-1",
                        true, 5)));

        mvc.perform(get("/api/owner/automations/activity/conversations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].phoneNumber").value(PHONE))
                .andExpect(jsonPath("$[0].unreadCount").value(2))
                .andExpect(jsonPath("$[0].givenName").value("Jane"))
                .andExpect(jsonPath("$[0].familyName").value("Doe"))
                .andExpect(jsonPath("$[0].smsConsent").value(true))
                .andExpect(jsonPath("$[0].squareProfileUrl").value("https://app.squareup.com/dashboard/customers/directory/customer/cust-1"))
                .andExpect(jsonPath("$[0].vip").value(true))
                .andExpect(jsonPath("$[0].visitCount").value(5))
                .andExpect(jsonPath("$[0].blocked").value(false))
                .andExpect(jsonPath("$[0].optedOut").value(false))
                .andExpect(jsonPath("$[0].clickedGoogleReview").value(false))
                .andExpect(jsonPath("$[0].clickedFeedbackForm").value(false))
                .andExpect(jsonPath("$[0].flaggedAsSpam").value(false));
    }

    @Test
    @DisplayName("GET /conversations marks a row blocked when its phone number is in the blocked-number table")
    void conversationsMarksBlockedNumbers() throws Exception {
        when(service.conversations()).thenReturn(List.of(
                new FakeConversationSummary(PHONE, Instant.now(), "hi", "INBOUND", 0L)));
        when(contactsService.resolveDisplayNames(List.of(PHONE))).thenReturn(Map.of());
        when(blockedNumberRepository.findByPhoneNumberIn(List.of(PHONE)))
                .thenReturn(List.of(BlockedNumber.builder().phoneNumber(PHONE).build()));

        mvc.perform(get("/api/owner/automations/activity/conversations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].blocked").value(true))
                .andExpect(jsonPath("$[0].optedOut").value(false));
    }

    @Test
    @DisplayName("GET /conversations marks a row optedOut (and blocked) when the block was source STOP_REQUEST")
    void conversationsMarksOptedOutForStopRequestSource() throws Exception {
        when(service.conversations()).thenReturn(List.of(
                new FakeConversationSummary(PHONE, Instant.now(), "STOP", "INBOUND", 0L)));
        when(contactsService.resolveDisplayNames(List.of(PHONE))).thenReturn(Map.of());
        when(blockedNumberRepository.findByPhoneNumberIn(List.of(PHONE))).thenReturn(List.of(
                BlockedNumber.builder().phoneNumber(PHONE).source(BlockedNumber.SOURCE_STOP_REQUEST).build()));

        mvc.perform(get("/api/owner/automations/activity/conversations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].blocked").value(true))
                .andExpect(jsonPath("$[0].optedOut").value(true));
    }

    @Test
    @DisplayName("GET /conversations marks a row's clicked-link flags from the batch lookups, independently")
    void conversationsMarksClickedLinkTargets() throws Exception {
        when(service.conversations()).thenReturn(List.of(
                new FakeConversationSummary(PHONE, Instant.now(), "hi", "INBOUND", 0L)));
        when(contactsService.resolveDisplayNames(List.of(PHONE))).thenReturn(Map.of());
        when(service.phoneNumbersWithClickedLinkTarget(List.of(PHONE), "GOOGLE_REVIEW"))
                .thenReturn(java.util.Set.of(PHONE));
        when(service.phoneNumbersWithClickedLinkTarget(List.of(PHONE), "FEEDBACK_FORM"))
                .thenReturn(java.util.Set.of());

        mvc.perform(get("/api/owner/automations/activity/conversations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].clickedGoogleReview").value(true))
                .andExpect(jsonPath("$[0].clickedFeedbackForm").value(false));
    }

    @Test
    @DisplayName("GET /conversations marks a row flagged-as-spam when the batch lookup finds it")
    void conversationsMarksFlaggedAsSpam() throws Exception {
        when(service.conversations()).thenReturn(List.of(
                new FakeConversationSummary(PHONE, Instant.now(), "hi", "INBOUND", 0L)));
        when(contactsService.resolveDisplayNames(List.of(PHONE))).thenReturn(Map.of());
        when(service.phoneNumbersFlaggedAsSpam(List.of(PHONE))).thenReturn(java.util.Set.of(PHONE));

        mvc.perform(get("/api/owner/automations/activity/conversations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].flaggedAsSpam").value(true));
    }

    @Test
    @DisplayName("GET /conversations/paged returns items + nextCursor + hasMore=true when a full page comes back")
    void conversationsPagedReturnsFullPageWithHasMoreTrue() throws Exception {
        Instant t1 = Instant.parse("2026-08-01T10:00:00Z");
        when(service.conversationsPage(null, 2)).thenReturn(List.of(
                new FakeConversationSummary(PHONE, t1, "hi", "INBOUND", 1L),
                new FakeConversationSummary("+15559998877", t1.minusSeconds(60), "yo", "OUTBOUND", 0L)));
        when(contactsService.resolveDisplayNames(any())).thenReturn(Map.of());

        mvc.perform(get("/api/owner/automations/activity/conversations/paged").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].phoneNumber").value(PHONE))
                .andExpect(jsonPath("$.nextCursor").value(t1.minusSeconds(60).toString()))
                .andExpect(jsonPath("$.hasMore").value(true));

        verify(service).conversationsPage(null, 2);
    }

    @Test
    @DisplayName("GET /conversations/paged returns hasMore=false and null nextCursor for a short/empty final page")
    void conversationsPagedReturnsHasMoreFalseForShortPage() throws Exception {
        when(service.conversationsPage(any(), eq(10))).thenReturn(List.of());
        when(contactsService.resolveDisplayNames(any())).thenReturn(Map.of());

        mvc.perform(get("/api/owner/automations/activity/conversations/paged")
                        .param("cursor", "2026-08-01T10:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.nextCursor").doesNotExist())
                .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    @DisplayName("GET /conversations/paged clamps an out-of-range limit into [1, 50]")
    void conversationsPagedClampsLimit() throws Exception {
        when(service.conversationsPage(null, 50)).thenReturn(List.of());
        when(contactsService.resolveDisplayNames(any())).thenReturn(Map.of());

        mvc.perform(get("/api/owner/automations/activity/conversations/paged").param("limit", "9999"))
                .andExpect(status().isOk());

        verify(service).conversationsPage(null, 50);
    }

    @Test
    @DisplayName("GET /conversations/{phoneNumber}/summary returns the enriched single-conversation DTO")
    void conversationSummaryReturnsEnrichedDto() throws Exception {
        when(service.conversationSummary(PHONE)).thenReturn(Optional.of(
                new FakeConversationSummary(PHONE, Instant.now(), "hi", "INBOUND", 3L)));
        when(contactsService.resolveDisplayNames(List.of(PHONE)))
                .thenReturn(Map.of(PHONE, new MarketingContactsService.ContactNameInfo(
                        "Jane", "Doe", true, null, false, null)));

        mvc.perform(get("/api/owner/automations/activity/conversations/{phoneNumber}/summary", PHONE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phoneNumber").value(PHONE))
                .andExpect(jsonPath("$.unreadCount").value(3))
                .andExpect(jsonPath("$.givenName").value("Jane"));
    }

    @Test
    @DisplayName("GET /conversations/{phoneNumber}/summary 404s when the phone number has no messages")
    void conversationSummaryReturns404WhenNotFound() throws Exception {
        when(service.conversationSummary(PHONE)).thenReturn(Optional.empty());

        mvc.perform(get("/api/owner/automations/activity/conversations/{phoneNumber}/summary", PHONE))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /conversations/{phoneNumber}/unread delegates to the service")
    void markThreadUnreadDelegates() throws Exception {
        mvc.perform(post("/api/owner/automations/activity/conversations/{phoneNumber}/unread", PHONE))
                .andExpect(status().isOk());

        verify(service).markThreadUnread(PHONE);
    }

    @Test
    @DisplayName("POST /conversations/{phoneNumber}/block saves a normalized BlockedNumber row")
    void blockNumberSavesNormalized() throws Exception {
        mvc.perform(post("/api/owner/automations/activity/conversations/{phoneNumber}/block", "(555) 123-4567"))
                .andExpect(status().isOk());

        ArgumentCaptor<BlockedNumber> captor = ArgumentCaptor.forClass(BlockedNumber.class);
        verify(blockedNumberRepository).save(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getPhoneNumber()).isEqualTo(PHONE);
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getSource()).isEqualTo(BlockedNumber.SOURCE_MANUAL);
        verify(events).broadcast(PHONE);
    }

    @Test
    @DisplayName("DELETE /conversations/{phoneNumber}/block removes the normalized phone number")
    void unblockNumberDeletesNormalized() throws Exception {
        mvc.perform(delete("/api/owner/automations/activity/conversations/{phoneNumber}/block", "(555) 123-4567"))
                .andExpect(status().isOk());

        verify(blockedNumberRepository).deleteById(PHONE);
        verify(events).broadcast(PHONE);
    }

    @Test
    @DisplayName("GET /stream subscribes to the live-update broadcaster")
    void streamSubscribesToBroadcaster() throws Exception {
        org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter =
                new org.springframework.web.servlet.mvc.method.annotation.SseEmitter();
        when(events.subscribe()).thenReturn(emitter);

        mvc.perform(get("/api/owner/automations/activity/stream"))
                .andExpect(status().isOk());

        verify(events).subscribe();
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

    @Test
    @DisplayName("POST /reply-with-media sends via TwilioSmsService.sendManualWithMedia and returns the result")
    void replyWithMediaSendsManualMessageWithFiles() throws Exception {
        when(smsService.sendManualWithMedia(eq(PHONE), eq("check this out"), any()))
                .thenReturn(new TwilioSmsService.SmsSendResult(true, null));
        MockMultipartFile file = new MockMultipartFile("files", "photo.jpg", "image/jpeg", new byte[]{1, 2, 3});

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/api/owner/automations/activity/reply-with-media")
                        .file(file)
                        .param("phoneNumber", PHONE)
                        .param("body", "check this out"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sent").value(true));

        verify(smsService).sendManualWithMedia(eq(PHONE), eq("check this out"), any());
    }

    @Test
    @DisplayName("GET /conversations/{phoneNumber} attaches media URLs from the batch lookup")
    void threadIncludesMedia() throws Exception {
        when(service.thread(PHONE)).thenReturn(List.of(
                SmsMessage.builder().id(1L).direction("INBOUND").phoneNumber(PHONE).body("here's a pic").status("RECEIVED").build()));
        when(mediaService.mediaForMessages(List.of(1L))).thenReturn(Map.of(
                1L, List.of(new SmsMediaService.MediaInfo("https://salon.akluxnails.com/api/public/sms-media/abc12", "image/jpeg"))));

        mvc.perform(get("/api/owner/automations/activity/conversations/{phoneNumber}", PHONE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].media[0].url").value("https://salon.akluxnails.com/api/public/sms-media/abc12"))
                .andExpect(jsonPath("$[0].media[0].contentType").value("image/jpeg"));
    }

    @Test
    @DisplayName("GET /conversations/{phoneNumber} attaches reactions from the batch lookup")
    void threadIncludesReactions() throws Exception {
        when(service.thread(PHONE)).thenReturn(List.of(
                SmsMessage.builder().id(1L).direction("OUTBOUND").phoneNumber(PHONE).body("hi").status("SENT").build()));
        when(reactionService.reactionsForMessages(List.of(1L))).thenReturn(Map.of(
                1L, List.of(new SmsReactionService.ReactionDto("❤️"))));

        mvc.perform(get("/api/owner/automations/activity/conversations/{phoneNumber}", PHONE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].reactions[0].emoji").value("❤️"));
    }

    @Test
    @DisplayName("GET /search maps each hit to a ConversationSearchHitDto")
    void searchMapsHits() throws Exception {
        when(service.searchConversations("appointment")).thenReturn(List.of(
                new ConversationSearchHit(PHONE, "running late for my appointment", "INBOUND", Instant.now())));

        mvc.perform(get("/api/owner/automations/activity/search").param("q", "appointment"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].phoneNumber").value(PHONE))
                .andExpect(jsonPath("$[0].snippet").value("running late for my appointment"))
                .andExpect(jsonPath("$[0].direction").value("INBOUND"));
    }
}
