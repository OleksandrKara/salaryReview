package com.salonreview.sms;

import com.salonreview.domain.Provider;
import com.salonreview.domain.SmsReplyFlow;
import com.salonreview.domain.TwilioSmsConfig;
import com.salonreview.repo.SmsReplyFlowRepository;
import com.salonreview.repo.TwilioSmsConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Durable, DB-backed delayed-send + expiry poller — see openspec/changes/sms-automations-hub
 * design.md D3.
 */
class SmsReplyFlowSchedulerTest {

    private static final String PHONE = "+15551234567";
    private static final Long BUSINESS_ID = 1L;

    private SmsReplyFlowRepository repository;
    private TwilioSmsService smsService;
    private TechnicianNameResolver technicianNameResolver;
    private TwilioSmsConfigRepository twilioConfigs;
    private SmsReplyFlowScheduler scheduler;

    @BeforeEach
    void setUp() {
        repository = mock(SmsReplyFlowRepository.class);
        smsService = mock(TwilioSmsService.class);
        technicianNameResolver = mock(TechnicianNameResolver.class);
        when(technicianNameResolver.resolveProviderForCustomer(any(), any(), any())).thenReturn(Optional.empty());
        twilioConfigs = mock(TwilioSmsConfigRepository.class);
        when(twilioConfigs.findAll()).thenReturn(List.of(TwilioSmsConfig.builder().businessId(BUSINESS_ID).build()));
        scheduler = new SmsReplyFlowScheduler(repository, smsService, technicianNameResolver, twilioConfigs);
    }

    private static SmsReplyFlow flow(String state) {
        return SmsReplyFlow.builder()
                .id(1L).businessId(BUSINESS_ID).automationKey("checkout_review_request").phoneNumber(PHONE)
                .customerName("Jane").state(state).sendDueAt(Instant.now()).build();
    }

    @Test
    @DisplayName("due AWAITING_SEND row: send succeeds → transitions to AWAITING_REPLY with a 24h expiry set")
    void dueRowSendsAndTransitions() {
        SmsReplyFlow due = flow(SmsReplyFlow.STATE_AWAITING_SEND);
        when(repository.findByBusinessIdAndStateAndSendDueAtBefore(eq(BUSINESS_ID), eq(SmsReplyFlow.STATE_AWAITING_SEND), any()))
                .thenReturn(List.of(due));
        when(smsService.sendTemplated(eq(BUSINESS_ID), eq("checkout_rating_request_no_technician"), eq(PHONE), any()))
                .thenReturn(new TwilioSmsService.SmsSendResult(true, null));

        scheduler.sendDueRatingRequests();

        assertThat(due.getState()).isEqualTo(SmsReplyFlow.STATE_AWAITING_REPLY);
        assertThat(due.getReplyExpiresAt()).isAfter(Instant.now().plusSeconds(23 * 3600));
        verify(repository).save(due);
        verify(smsService).sendTemplated(BUSINESS_ID, "checkout_rating_request_no_technician", PHONE, Map.of("greeting", "Hi Jane!"));
    }

    @Test
    @DisplayName("due AWAITING_SEND row: send fails → transitions straight to EXPIRED, no reply window")
    void dueRowSendFailureExpires() {
        SmsReplyFlow due = flow(SmsReplyFlow.STATE_AWAITING_SEND);
        when(repository.findByBusinessIdAndStateAndSendDueAtBefore(eq(BUSINESS_ID), eq(SmsReplyFlow.STATE_AWAITING_SEND), any()))
                .thenReturn(List.of(due));
        when(smsService.sendTemplated(eq(BUSINESS_ID), eq("checkout_rating_request_no_technician"), eq(PHONE), any()))
                .thenReturn(new TwilioSmsService.SmsSendResult(false, "not_configured"));

        scheduler.sendDueRatingRequests();

        assertThat(due.getState()).isEqualTo(SmsReplyFlow.STATE_EXPIRED);
        assertThat(due.getReplyExpiresAt()).isNull();
        verify(repository).save(due);
    }

    @Test
    @DisplayName("no customer name on the flow → variables map is empty, not a name-shaped map with null")
    void noNameSendsEmptyVariables() {
        SmsReplyFlow due = flow(SmsReplyFlow.STATE_AWAITING_SEND);
        due.setCustomerName(null);
        when(repository.findByBusinessIdAndStateAndSendDueAtBefore(eq(BUSINESS_ID), eq(SmsReplyFlow.STATE_AWAITING_SEND), any()))
                .thenReturn(List.of(due));
        when(smsService.sendTemplated(any(), any(), any(), any())).thenReturn(new TwilioSmsService.SmsSendResult(true, null));

        scheduler.sendDueRatingRequests();

        verify(smsService).sendTemplated(BUSINESS_ID, "checkout_rating_request_no_technician", PHONE, Map.of("greeting", "Hi!"));
    }

    @Test
    @DisplayName("real per-business iteration: two businesses' due flows both get sent, each with its own businessId")
    void iteratesEveryBusinessWithATwilioConfig() {
        Long otherBusinessId = 2L;
        when(twilioConfigs.findAll()).thenReturn(List.of(
                TwilioSmsConfig.builder().businessId(BUSINESS_ID).build(),
                TwilioSmsConfig.builder().businessId(otherBusinessId).build()));
        SmsReplyFlow dueA = flow(SmsReplyFlow.STATE_AWAITING_SEND);
        SmsReplyFlow dueB = SmsReplyFlow.builder()
                .id(2L).businessId(otherBusinessId).automationKey("checkout_review_request")
                .phoneNumber("+15559998888").customerName("Bob").state(SmsReplyFlow.STATE_AWAITING_SEND)
                .sendDueAt(Instant.now()).build();
        when(repository.findByBusinessIdAndStateAndSendDueAtBefore(eq(BUSINESS_ID), eq(SmsReplyFlow.STATE_AWAITING_SEND), any()))
                .thenReturn(List.of(dueA));
        when(repository.findByBusinessIdAndStateAndSendDueAtBefore(eq(otherBusinessId), eq(SmsReplyFlow.STATE_AWAITING_SEND), any()))
                .thenReturn(List.of(dueB));
        when(smsService.sendTemplated(any(), any(), any(), any())).thenReturn(new TwilioSmsService.SmsSendResult(true, null));

        scheduler.sendDueRatingRequests();

        verify(smsService).sendTemplated(BUSINESS_ID, "checkout_rating_request_no_technician", PHONE, Map.of("greeting", "Hi Jane!"));
        verify(smsService).sendTemplated(otherBusinessId, "checkout_rating_request_no_technician", "+15559998888", Map.of("greeting", "Hi Bob!"));
        assertThat(dueA.getState()).isEqualTo(SmsReplyFlow.STATE_AWAITING_REPLY);
        assertThat(dueB.getState()).isEqualTo(SmsReplyFlow.STATE_AWAITING_REPLY);
    }

    @Test
    @DisplayName("not-yet-due row is left untouched — repository query itself is the filter")
    void notDueRowUntouched() {
        when(repository.findByBusinessIdAndStateAndSendDueAtBefore(eq(BUSINESS_ID), eq(SmsReplyFlow.STATE_AWAITING_SEND), any()))
                .thenReturn(List.of());

        scheduler.sendDueRatingRequests();

        verifyNoInteractions(smsService);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("past-expiry AWAITING_REPLY row transitions to EXPIRED")
    void staleReplyWindowExpires() {
        SmsReplyFlow stale = flow(SmsReplyFlow.STATE_AWAITING_REPLY);
        when(repository.findByBusinessIdAndStateAndReplyExpiresAtBefore(eq(BUSINESS_ID), eq(SmsReplyFlow.STATE_AWAITING_REPLY), any()))
                .thenReturn(List.of(stale));

        scheduler.expireStaleReplyWindows();

        assertThat(stale.getState()).isEqualTo(SmsReplyFlow.STATE_EXPIRED);
        verify(repository).save(stale);
    }

    @Test
    @DisplayName("technician resolves for the flow's customer → threaded into the vars map, provider_id persisted on the flow")
    void resolvedTechnicianIsThreadedIntoVars() {
        SmsReplyFlow due = flow(SmsReplyFlow.STATE_AWAITING_SEND);
        due.setSquareCustomerId("cust1");
        when(repository.findByBusinessIdAndStateAndSendDueAtBefore(eq(BUSINESS_ID), eq(SmsReplyFlow.STATE_AWAITING_SEND), any()))
                .thenReturn(List.of(due));
        Provider susan = Provider.builder().id(7L).displayName("Susan").build();
        when(technicianNameResolver.resolveProviderForCustomer(eq(BUSINESS_ID), eq("cust1"), any())).thenReturn(Optional.of(susan));
        when(smsService.sendTemplated(eq(BUSINESS_ID), eq("checkout_rating_request_with_technician"), eq(PHONE), any()))
                .thenReturn(new TwilioSmsService.SmsSendResult(true, null));

        scheduler.sendDueRatingRequests();

        verify(smsService).sendTemplated(BUSINESS_ID, "checkout_rating_request_with_technician", PHONE,
                Map.of("greeting", "Hi Jane!", "technician", "Susan"));
        assertThat(due.getProviderId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("no stale rows → nothing saved")
    void noStaleRowsNoOp() {
        when(repository.findByBusinessIdAndStateAndReplyExpiresAtBefore(eq(BUSINESS_ID), eq(SmsReplyFlow.STATE_AWAITING_REPLY), any()))
                .thenReturn(List.of());

        scheduler.expireStaleReplyWindows();

        verify(repository, never()).save(any());
    }
}
