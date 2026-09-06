package com.salonreview.sms;

import com.salonreview.domain.MailchimpConfig;
import com.salonreview.domain.WinbackEmailSend;
import com.salonreview.repo.WinbackEmailSendRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MailchimpBatchCampaignServiceTest {

    private static final Long BUSINESS_ID = 2L;
    private static final String AUTOMATION_KEY = "pmu_one_off_blast";

    private MailchimpClient client;
    private MailchimpEmailTemplateService templateService;
    private WinbackEmailSendRepository sendRepository;
    private MailchimpBatchCampaignService service;
    private MailchimpConfig config;

    @BeforeEach
    void setUp() {
        client = mock(MailchimpClient.class);
        templateService = mock(MailchimpEmailTemplateService.class);
        sendRepository = mock(WinbackEmailSendRepository.class);
        service = new MailchimpBatchCampaignService(client, templateService, sendRepository);
        config = MailchimpConfig.builder().businessId(BUSINESS_ID).apiKey("k-us1").audienceId("a1")
                .fromName("Lucy").fromEmail("lucy@example.com").replyToEmail("lucy@example.com").build();
        when(templateService.loadRaw(eq(BUSINESS_ID), anyString())).thenReturn(Optional.of("<html>*|FNAME|*</html>"));
    }

    private static MailchimpBatchCampaignService.Recipient recipient(String id, String email, String name) {
        return new MailchimpBatchCampaignService.Recipient(id, email, name);
    }

    @Test
    @DisplayName("real send: upserts members, creates a static segment + one campaign, sets content once, sends once")
    void realSendCreatesOneSharedCampaign() throws Exception {
        List<MailchimpBatchCampaignService.Recipient> recipients = List.of(
                recipient("cust1", "jane@example.com", "Jane"),
                recipient("cust2", "bob@example.com", "Bob"));
        when(client.createStaticSegment(eq(config), anyString(), any())).thenReturn(42L);
        when(client.createCampaignForSegment(eq(config), eq(42L), any(), any(), any())).thenReturn("campaign-1");

        MailchimpBatchCampaignService.BatchSendResult result = service.send(BUSINESS_ID, AUTOMATION_KEY, config,
                "pmu-blast-2026-09", "Subject", "Preview", "Title", recipients);

        assertThat(result.state()).isEqualTo("SENT");
        assertThat(result.segmentId()).isEqualTo(42L);
        assertThat(result.campaignId()).isEqualTo("campaign-1");
        assertThat(result.recipientCount()).isEqualTo(2);

        verify(client, times(1)).batchUpsertMembers(eq(config), any());
        verify(client, times(1)).createStaticSegment(eq(config), anyString(), any());
        verify(client, times(1)).createCampaignForSegment(eq(config), eq(42L), any(), any(), any());
        verify(client, times(1)).setContent(config, "campaign-1", "<html>*|FNAME|*</html>");
        verify(client, times(1)).send(config, "campaign-1");
    }

    @Test
    @DisplayName("real send: writes one SENT WinbackEmailSend row per recipient, all sharing the one campaign id")
    void writesOneRowPerRecipientSharingCampaignId() throws Exception {
        List<MailchimpBatchCampaignService.Recipient> recipients = List.of(
                recipient("cust1", "jane@example.com", "Jane"),
                recipient("cust2", "bob@example.com", "Bob"));
        when(client.createStaticSegment(any(), anyString(), any())).thenReturn(42L);
        when(client.createCampaignForSegment(any(), eq(42L), any(), any(), any())).thenReturn("campaign-1");

        service.send(BUSINESS_ID, AUTOMATION_KEY, config, "seg", "Subject", "Preview", "Title", recipients);

        ArgumentCaptor<WinbackEmailSend> captor = ArgumentCaptor.forClass(WinbackEmailSend.class);
        verify(sendRepository, times(2)).save(captor.capture());
        List<WinbackEmailSend> saved = captor.getAllValues();
        assertThat(saved).extracting(WinbackEmailSend::getMailchimpCampaignId).containsExactly("campaign-1", "campaign-1");
        assertThat(saved).extracting(WinbackEmailSend::getState).containsExactly(WinbackEmailSend.STATE_SENT, WinbackEmailSend.STATE_SENT);
        assertThat(saved).extracting(WinbackEmailSend::getSquareCustomerId).containsExactly("cust1", "cust2");
    }

    @Test
    @DisplayName("empty recipient list -> SKIPPED_NO_RECIPIENTS, no Mailchimp calls")
    void emptyRecipientsSkipped() {
        MailchimpBatchCampaignService.BatchSendResult result = service.send(BUSINESS_ID, AUTOMATION_KEY, config,
                "seg", "Subject", "Preview", "Title", List.of());

        assertThat(result.state()).isEqualTo("SKIPPED_NO_RECIPIENTS");
        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("no batch template registered -> SKIPPED_NO_TEMPLATE, no Mailchimp calls")
    void noTemplateSkipped() {
        when(templateService.loadRaw(eq(BUSINESS_ID), anyString())).thenReturn(Optional.empty());

        MailchimpBatchCampaignService.BatchSendResult result = service.send(BUSINESS_ID, AUTOMATION_KEY, config,
                "seg", "Subject", "Preview", "Title", List.of(recipient("cust1", "jane@example.com", "Jane")));

        assertThat(result.state()).isEqualTo("SKIPPED_NO_TEMPLATE");
        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("a failure partway through (e.g. segment creation) -> SEND_FAILED, no WinbackEmailSend rows written")
    void failurePartwayRecordsSendFailedAndWritesNoRows() throws Exception {
        when(client.createStaticSegment(any(), anyString(), any())).thenThrow(new RuntimeException("Mailchimp API error"));

        MailchimpBatchCampaignService.BatchSendResult result = service.send(BUSINESS_ID, AUTOMATION_KEY, config,
                "seg", "Subject", "Preview", "Title", List.of(recipient("cust1", "jane@example.com", "Jane")));

        assertThat(result.state()).isEqualTo("SEND_FAILED");
        assertThat(result.detail()).contains("Mailchimp API error");
        verify(sendRepository, never()).save(any());
    }
}
