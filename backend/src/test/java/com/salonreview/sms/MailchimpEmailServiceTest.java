package com.salonreview.sms;

import com.salonreview.domain.MailchimpConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Covers the {@code send()} retry added 2026-09-05 after the color-booster winback one-off found
 * Mailchimp's own recipient-segment resolution occasionally still in flight when a send fires
 * immediately after campaign creation ("recipients not ready") under a fast back-to-back loop. */
class MailchimpEmailServiceTest {

    private MailchimpClient client;
    private MailchimpEmailService service;
    private MailchimpConfig config;

    @BeforeEach
    void setUp() {
        client = mock(MailchimpClient.class);
        service = new MailchimpEmailService(client);
        config = MailchimpConfig.builder().businessId(1L).apiKey("k-us1").audienceId("a1")
                .fromName("Lucy").fromEmail("lucy@example.com").replyToEmail("lucy@example.com").build();
    }

    @Test
    @DisplayName("send succeeds first try — no retry, campaign id returned")
    void sendsSuccessfullyFirstTry() throws Exception {
        when(client.createSingleRecipientCampaign(any(), any(), any(), any(), any())).thenReturn("campaign-1");
        doNothing().when(client).send(config, "campaign-1");

        String campaignId = service.sendWinbackEmail(config, "jane@example.com", "Subject", "Preview", "Title", "<html></html>");

        assertThat(campaignId).isEqualTo("campaign-1");
        verify(client, times(1)).send(config, "campaign-1");
    }

    @Test
    @DisplayName("\"recipients not ready\" on first attempt, succeeds on retry -> no exception, send called twice")
    void retriesOnRecipientsNotReady() throws Exception {
        when(client.createSingleRecipientCampaign(any(), any(), any(), any(), any())).thenReturn("campaign-1");
        doThrow(new IOException("Mailchimp API failed to send campaign (400): recipients not ready"))
                .doNothing()
                .when(client).send(config, "campaign-1");

        String campaignId = service.sendWinbackEmail(config, "jane@example.com", "Subject", "Preview", "Title", "<html></html>");

        assertThat(campaignId).isEqualTo("campaign-1");
        verify(client, times(2)).send(config, "campaign-1");
    }

    @Test
    @DisplayName("\"recipients not ready\" on every attempt -> throws after exhausting retries, tried exactly 3 times")
    void givesUpAfterMaxAttempts() throws Exception {
        when(client.createSingleRecipientCampaign(any(), any(), any(), any(), any())).thenReturn("campaign-1");
        doThrow(new IOException("Mailchimp API failed to send campaign (400): recipients not ready"))
                .when(client).send(config, "campaign-1");

        assertThatThrownBy(() -> service.sendWinbackEmail(config, "jane@example.com", "Subject", "Preview", "Title", "<html></html>"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("recipients not ready");

        verify(client, times(3)).send(eq(config), eq("campaign-1"));
    }

    @Test
    @DisplayName("a different (non-retryable) send failure throws immediately, no retry")
    void nonRetryableFailureThrowsImmediately() throws Exception {
        when(client.createSingleRecipientCampaign(any(), any(), any(), any(), any())).thenReturn("campaign-1");
        doThrow(new IOException("Mailchimp API failed to send campaign (400): some other real error"))
                .when(client).send(config, "campaign-1");

        assertThatThrownBy(() -> service.sendWinbackEmail(config, "jane@example.com", "Subject", "Preview", "Title", "<html></html>"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("some other real error");

        verify(client, times(1)).send(eq(config), eq("campaign-1"));
    }
}
