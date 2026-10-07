package com.salonreview.sms;

import com.salonreview.domain.MailchimpConfig;
import com.salonreview.domain.MailchimpDeferredSend;
import com.salonreview.repo.MailchimpConfigRepository;
import com.salonreview.repo.MailchimpDeferredSendRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class MailchimpDeferredSendSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-10-07T18:00:00Z");
    private static final IOException NOT_READY = new IOException("Mailchimp API failed to send campaign (400): recipients not ready");

    private MailchimpDeferredSendRepository repo;
    private MailchimpClient client;
    private MailchimpConfig config;
    private MailchimpDeferredSendScheduler scheduler;
    private MailchimpDeferredSend row;

    @BeforeEach
    void setUp() {
        repo = mock(MailchimpDeferredSendRepository.class);
        MailchimpConfigRepository configs = mock(MailchimpConfigRepository.class);
        client = mock(MailchimpClient.class);
        config = MailchimpConfig.builder().businessId(1L).apiKey("k-us1").audienceId("a1")
                .fromName("Lucy").fromEmail("lucy@example.com").replyToEmail("lucy@example.com").build();
        when(configs.findByBusinessId(1L)).thenReturn(Optional.of(config));
        row = MailchimpDeferredSend.builder().id(5L).businessId(1L).campaignId("c-1").campaignTitle("t")
                .nextAttemptAt(NOW.minusSeconds(1)).build();
        when(repo.findByStateAndNextAttemptAtBefore(MailchimpDeferredSend.STATE_PENDING, NOW)).thenReturn(List.of(row));
        scheduler = new MailchimpDeferredSendScheduler(repo, configs, client, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void aDueCampaignIsSent() throws Exception {
        scheduler.run();
        verify(client).send(config, "c-1");
        assertThat(row.getState()).isEqualTo(MailchimpDeferredSend.STATE_SENT);
        assertThat(row.getFinishedAt()).isEqualTo(NOW);
    }

    @Test
    void stillNotReadyBacksOffAndStaysPending() throws Exception {
        doThrow(NOT_READY).when(client).send(config, "c-1");
        scheduler.run();
        assertThat(row.getState()).isEqualTo(MailchimpDeferredSend.STATE_PENDING);
        assertThat(row.getAttempts()).isEqualTo(1);
        assertThat(row.getNextAttemptAt()).isEqualTo(NOW.plus(Duration.ofMinutes(1)));
    }

    @Test
    void givesUpAfterTheLastAttempt() throws Exception {
        doThrow(NOT_READY).when(client).send(config, "c-1");
        row.setAttempts(MailchimpDeferredSendScheduler.MAX_ATTEMPTS - 1);
        scheduler.run();
        assertThat(row.getState()).isEqualTo(MailchimpDeferredSend.STATE_FAILED);
    }

    @Test
    void anyOtherFailureEndsItAtOnce() throws Exception {
        doThrow(new IOException("Mailchimp API failed to send campaign (400): campaign already sent")).when(client).send(config, "c-1");
        scheduler.run();
        assertThat(row.getState()).isEqualTo(MailchimpDeferredSend.STATE_FAILED);
        assertThat(row.getAttempts()).isEqualTo(1);
    }
}
