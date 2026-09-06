package com.salonreview.sms;

import com.salonreview.domain.MailchimpConfig;
import com.salonreview.domain.WinbackEmailSend;
import com.salonreview.repo.MailchimpConfigRepository;
import com.salonreview.repo.WinbackEmailSendRepository;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Covers the 2026-09-06 change to group pending rows by {@code (businessId, mailchimpCampaignId)}
 * and fetch each campaign's activity report exactly once — the shape a {@code
 * MailchimpBatchCampaignService} mass send needs (many rows sharing one campaign id), while still
 * behaving exactly as before for single-recipient automations (one row per group). */
class MailchimpActivitySyncSchedulerTest {

    private static final Long BUSINESS_ID = 1L;

    private WinbackEmailSendRepository sendRepository;
    private MailchimpConfigRepository configRepository;
    private MailchimpClient client;
    private MailchimpActivitySyncScheduler scheduler;
    private MailchimpConfig config;

    @BeforeEach
    void setUp() {
        sendRepository = mock(WinbackEmailSendRepository.class);
        configRepository = mock(MailchimpConfigRepository.class);
        client = mock(MailchimpClient.class);
        scheduler = new MailchimpActivitySyncScheduler(sendRepository, configRepository, client);
        config = MailchimpConfig.builder().businessId(BUSINESS_ID).apiKey("k-us1").audienceId("a1")
                .fromName("Lucy").fromEmail("lucy@example.com").replyToEmail("lucy@example.com").build();
        when(configRepository.findByBusinessId(BUSINESS_ID)).thenReturn(Optional.of(config));
    }

    private static WinbackEmailSend row(Long id, String campaignId, String email) {
        return WinbackEmailSend.builder().id(id).businessId(BUSINESS_ID).automationKey("k")
                .squareCustomerId("cust" + id).emailAddress(email).state(WinbackEmailSend.STATE_SENT)
                .mailchimpCampaignId(campaignId).build();
    }

    @Test
    @DisplayName("many rows sharing one campaign id -> exactly one fetchAllEmailActivity call, applied to every matching row")
    void groupsRowsByCampaignIntoOneFetch() throws Exception {
        WinbackEmailSend rowA = row(1L, "campaign-shared", "jane@example.com");
        WinbackEmailSend rowB = row(2L, "campaign-shared", "bob@example.com");
        when(sendRepository.findNeedingActivitySync(any())).thenReturn(List.of(rowA, rowB));
        Instant openedJane = Instant.parse("2026-09-06T10:00:00Z");
        Instant openedBob = Instant.parse("2026-09-06T11:00:00Z");
        when(client.fetchAllEmailActivity(config, "campaign-shared")).thenReturn(Map.of(
                "jane@example.com", new MailchimpClient.EmailActivity(openedJane, null),
                "bob@example.com", new MailchimpClient.EmailActivity(openedBob, null)));

        scheduler.sync();

        verify(client, times(1)).fetchAllEmailActivity(config, "campaign-shared");
        assertThat(rowA.getOpenedAt()).isEqualTo(openedJane);
        assertThat(rowB.getOpenedAt()).isEqualTo(openedBob);
        verify(sendRepository, times(1)).save(rowA);
        verify(sendRepository, times(1)).save(rowB);
    }

    @Test
    @DisplayName("rows under different campaign ids -> one fetch per distinct campaign, same as before this change")
    void differentCampaignsFetchedSeparately() throws Exception {
        WinbackEmailSend rowA = row(1L, "campaign-1", "jane@example.com");
        WinbackEmailSend rowB = row(2L, "campaign-2", "bob@example.com");
        when(sendRepository.findNeedingActivitySync(any())).thenReturn(List.of(rowA, rowB));
        when(client.fetchAllEmailActivity(config, "campaign-1")).thenReturn(Map.of());
        when(client.fetchAllEmailActivity(config, "campaign-2")).thenReturn(Map.of());

        scheduler.sync();

        verify(client, times(1)).fetchAllEmailActivity(config, "campaign-1");
        verify(client, times(1)).fetchAllEmailActivity(config, "campaign-2");
    }

    @Test
    @DisplayName("email match is case-insensitive against the campaign report's lower-cased keys")
    void matchesEmailCaseInsensitively() throws Exception {
        WinbackEmailSend rowA = row(1L, "campaign-1", "Jane@Example.com");
        when(sendRepository.findNeedingActivitySync(any())).thenReturn(List.of(rowA));
        Instant opened = Instant.parse("2026-09-06T10:00:00Z");
        when(client.fetchAllEmailActivity(config, "campaign-1"))
                .thenReturn(Map.of("jane@example.com", new MailchimpClient.EmailActivity(opened, null)));

        scheduler.sync();

        assertThat(rowA.getOpenedAt()).isEqualTo(opened);
    }

    @Test
    @DisplayName("a failed fetch for one campaign group doesn't block other groups from syncing")
    void failureInOneGroupDoesNotBlockOthers() throws Exception {
        WinbackEmailSend rowA = row(1L, "campaign-broken", "jane@example.com");
        WinbackEmailSend rowB = row(2L, "campaign-ok", "bob@example.com");
        when(sendRepository.findNeedingActivitySync(any())).thenReturn(List.of(rowA, rowB));
        when(client.fetchAllEmailActivity(config, "campaign-broken")).thenThrow(new RuntimeException("Mailchimp API error"));
        Instant opened = Instant.parse("2026-09-06T10:00:00Z");
        when(client.fetchAllEmailActivity(config, "campaign-ok"))
                .thenReturn(Map.of("bob@example.com", new MailchimpClient.EmailActivity(opened, null)));

        scheduler.sync();

        assertThat(rowA.getOpenedAt()).isNull();
        assertThat(rowB.getOpenedAt()).isEqualTo(opened);
        verify(sendRepository, never()).save(rowA);
        verify(sendRepository, times(1)).save(rowB);
    }

    @Test
    @DisplayName("no pending rows -> no Mailchimp calls at all")
    void nothingPendingSkipsEntirely() throws Exception {
        when(sendRepository.findNeedingActivitySync(any())).thenReturn(List.of());

        scheduler.sync();

        verify(client, never()).fetchAllEmailActivity(any(), any());
    }

    @Test
    @DisplayName("Mailchimp not configured for the business -> group skipped, no fetch attempted")
    void notConfiguredSkipsGroup() throws Exception {
        when(configRepository.findByBusinessId(BUSINESS_ID)).thenReturn(Optional.empty());
        WinbackEmailSend rowA = row(1L, "campaign-1", "jane@example.com");
        when(sendRepository.findNeedingActivitySync(any())).thenReturn(List.of(rowA));

        scheduler.sync();

        verify(client, never()).fetchAllEmailActivity(any(), any());
    }
}
