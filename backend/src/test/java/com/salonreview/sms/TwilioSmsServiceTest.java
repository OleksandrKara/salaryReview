package com.salonreview.sms;

import com.salonreview.domain.TwilioSmsConfig;
import com.salonreview.repo.BlockedNumberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import com.salonreview.domain.SmsMessage;
import com.salonreview.domain.SmsMessageMedia;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class TwilioSmsServiceTest {

    private static final String TRANSACTIONAL_KEY = "test_transactional";
    private static final String MARKETING_KEY = "test_marketing";
    private static final String PHONE = "+15551234567";
    private static final Long BUSINESS_ID = 1L;

    private SmsTemplateRegistry templateRegistry;
    private TwilioSmsConfigService configService;
    private SmsConsentRepository consentRepository;
    private SmsAutomationService automationService;
    private SmsMessageLogService messageLogService;
    private TwilioSmsClient client;
    private BlockedNumberRepository blockedNumberRepository;
    private SmsMediaService mediaService;
    private TwilioSmsService service;

    @BeforeEach
    void setUp() {
        templateRegistry = mock(SmsTemplateRegistry.class);
        configService = mock(TwilioSmsConfigService.class);
        consentRepository = mock(SmsConsentRepository.class);
        automationService = mock(SmsAutomationService.class);
        messageLogService = mock(SmsMessageLogService.class);
        client = mock(TwilioSmsClient.class);
        blockedNumberRepository = mock(BlockedNumberRepository.class);
        mediaService = mock(SmsMediaService.class);
        service = new TwilioSmsService(templateRegistry, configService, consentRepository, automationService,
                messageLogService, client, blockedNumberRepository, mediaService);

        when(automationService.isEnabled(any(), any())).thenReturn(true);
        when(blockedNumberRepository.existsById(any())).thenReturn(false);
        when(templateRegistry.find(TRANSACTIONAL_KEY))
                .thenReturn(new SmsTemplate(TRANSACTIONAL_KEY, SmsMessageClass.TRANSACTIONAL, vars -> "transactional body"));
        when(templateRegistry.find(MARKETING_KEY))
                .thenReturn(new SmsTemplate(MARKETING_KEY, SmsMessageClass.MARKETING, vars -> "marketing body"));
    }

    private static TwilioSmsConfig configured() {
        return TwilioSmsConfig.builder()
                .accountSid("AC123").apiKey("SK123").apiSecret("secret").fromPhoneNumber("+15559999999")
                .build();
    }

    @Test
    @DisplayName("unknown template key → skipped, no consent check, no send attempt")
    void unknownTemplateSkipped() throws Exception {
        var result = service.sendTemplated(BUSINESS_ID, "does_not_exist", PHONE, Map.of());

        assertThat(result.sent()).isFalse();
        assertThat(result.reason()).isEqualTo("unknown_template");
        verifyNoInteractions(consentRepository, client);
    }

    @Test
    @DisplayName("TRANSACTIONAL template sends regardless of consent")
    void transactionalSendsWithoutConsentCheck() throws Exception {
        when(configService.get(BUSINESS_ID)).thenReturn(configured());

        var result = service.sendTemplated(BUSINESS_ID, TRANSACTIONAL_KEY, PHONE, Map.of());

        assertThat(result.sent()).isTrue();
        verify(client).send(any(), eq(PHONE), eq("transactional body"));
        verifyNoInteractions(consentRepository);
    }

    @Test
    @DisplayName("MARKETING template blocked when consent is false")
    void marketingBlockedWithoutConsent() throws Exception {
        when(consentRepository.hasMarketingConsent(PHONE)).thenReturn(false);

        var result = service.sendTemplated(BUSINESS_ID, MARKETING_KEY, PHONE, Map.of());

        assertThat(result.sent()).isFalse();
        assertThat(result.reason()).isEqualTo("no_consent");
        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("MARKETING template sent when consent is true and credentials are configured")
    void marketingSentWithConsent() throws Exception {
        when(consentRepository.hasMarketingConsent(PHONE)).thenReturn(true);
        when(configService.get(BUSINESS_ID)).thenReturn(configured());

        var result = service.sendTemplated(BUSINESS_ID, MARKETING_KEY, PHONE, Map.of());

        assertThat(result.sent()).isTrue();
        verify(client).send(any(), eq(PHONE), eq("marketing body"));
    }

    @Test
    @DisplayName("unset credentials → not_configured, no HTTP attempt")
    void unconfiguredCredentialsSkipsSend() throws Exception {
        when(configService.get(BUSINESS_ID)).thenReturn(TwilioSmsConfig.builder().build());

        var result = service.sendTemplated(BUSINESS_ID, TRANSACTIONAL_KEY, PHONE, Map.of());

        assertThat(result.sent()).isFalse();
        assertThat(result.reason()).isEqualTo("not_configured");
        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("Twilio client failure → send_failed, never throws")
    void clientFailureReturnsSendFailed() throws Exception {
        when(configService.get(BUSINESS_ID)).thenReturn(configured());
        doThrow(new java.io.IOException("boom")).when(client).send(any(), any(), any());

        var result = service.sendTemplated(BUSINESS_ID, TRANSACTIONAL_KEY, PHONE, Map.of());

        assertThat(result.sent()).isFalse();
        assertThat(result.reason()).isEqualTo("send_failed");
    }

    @Test
    @DisplayName("Disabled automation → automation_disabled, no consent check, no send attempt")
    void disabledAutomationSkipsSend() throws Exception {
        String key = "test_gated";
        when(templateRegistry.find(key)).thenReturn(
                new SmsTemplate(key, SmsMessageClass.TRANSACTIONAL, "some_automation", vars -> "gated body"));
        when(automationService.isEnabled(BUSINESS_ID, "some_automation")).thenReturn(false);

        var result = service.sendTemplated(BUSINESS_ID, key, PHONE, Map.of());

        assertThat(result.sent()).isFalse();
        assertThat(result.reason()).isEqualTo("automation_disabled");
        verifyNoInteractions(consentRepository, client);
    }

    @Test
    @DisplayName("Every send attempt, including blocked ones, is logged to the activity log")
    void everyAttemptIsLogged() throws Exception {
        when(configService.get(BUSINESS_ID)).thenReturn(configured());

        service.sendTemplated(BUSINESS_ID, TRANSACTIONAL_KEY, PHONE, Map.of());
        verify(messageLogService).logOutbound(eq(BUSINESS_ID), eq(TRANSACTIONAL_KEY), any(), eq(PHONE), eq("transactional body"),
                eq(true), eq(null), any());

        service.sendTemplated(BUSINESS_ID, "does_not_exist", PHONE, Map.of());
        verify(messageLogService).logOutbound(eq(BUSINESS_ID), eq("does_not_exist"), eq(null), eq(PHONE), eq(""), eq(false),
                eq("unknown_template"), eq(null));
    }

    @Test
    @DisplayName("sendManual: sends a freeform body directly, bypassing templates/automation/consent")
    void sendManualSendsDirectly() throws Exception {
        when(configService.get(BUSINESS_ID)).thenReturn(configured());

        var result = service.sendManual(BUSINESS_ID, PHONE, "hand-typed reply");

        assertThat(result.sent()).isTrue();
        verify(client).send(any(), eq(PHONE), eq("hand-typed reply"));
        verify(messageLogService).logOutbound(eq(BUSINESS_ID), eq(null), eq(null), eq(PHONE), eq("hand-typed reply"),
                eq(true), eq(null), any());
        verifyNoInteractions(templateRegistry, consentRepository, automationService);
    }

    @Test
    @DisplayName("sendManual: unset credentials → not_configured, no HTTP attempt")
    void sendManualUnconfiguredSkips() throws Exception {
        when(configService.get(BUSINESS_ID)).thenReturn(TwilioSmsConfig.builder().build());

        var result = service.sendManual(BUSINESS_ID, PHONE, "hi");

        assertThat(result.sent()).isFalse();
        assertThat(result.reason()).isEqualTo("not_configured");
        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("sendManual: Twilio client failure → send_failed, never throws")
    void sendManualClientFailureReturnsSendFailed() throws Exception {
        when(configService.get(BUSINESS_ID)).thenReturn(configured());
        doThrow(new java.io.IOException("boom")).when(client).send(any(), any(), any());

        var result = service.sendManual(BUSINESS_ID, PHONE, "hi");

        assertThat(result.sent()).isFalse();
        assertThat(result.reason()).isEqualTo("send_failed");
    }

    @Test
    @DisplayName("sendTemplated: blocked number → skipped before consent/automation checks, no send attempt")
    void blockedNumberSkipsTemplatedSend() throws Exception {
        when(blockedNumberRepository.existsById(PHONE)).thenReturn(true);

        var result = service.sendTemplated(BUSINESS_ID, TRANSACTIONAL_KEY, PHONE, Map.of());

        assertThat(result.sent()).isFalse();
        assertThat(result.reason()).isEqualTo("blocked");
        verifyNoInteractions(client);
        verify(messageLogService).logOutbound(eq(BUSINESS_ID), eq(TRANSACTIONAL_KEY), any(), eq(PHONE), eq("transactional body"),
                eq(false), eq("blocked"), eq(null));
    }

    @Test
    @DisplayName("sendManual: blocked number → skipped, no send attempt")
    void blockedNumberSkipsManualSend() throws Exception {
        when(blockedNumberRepository.existsById(PHONE)).thenReturn(true);

        var result = service.sendManual(BUSINESS_ID, PHONE, "hi");

        assertThat(result.sent()).isFalse();
        assertThat(result.reason()).isEqualTo("blocked");
        verifyNoInteractions(client, configService);
    }

    @Test
    @DisplayName("sendManualWithMedia: stores each attachment against the reserved row, then sends with media URLs")
    void sendManualWithMediaStoresThenSends() throws Exception {
        when(configService.get(BUSINESS_ID)).thenReturn(configured());
        SmsMessage reserved = SmsMessage.builder().id(55L).build();
        when(messageLogService.logOutbound(eq(BUSINESS_ID), eq(null), eq(null), eq(PHONE), eq("here's a photo"), eq(false), eq("pending"), eq(null)))
                .thenReturn(reserved);
        SmsMessageMedia media = SmsMessageMedia.builder().id(1L).smsMessageId(55L).accessToken("abc12").build();
        when(mediaService.store(eq(55L), eq("image/jpeg"), any())).thenReturn(media);
        when(mediaService.publicUrl(media)).thenReturn("https://salon.akluxnails.com/api/public/sms-media/abc12");
        when(client.send(any(), eq(PHONE), eq("here's a photo"), eq(List.of("https://salon.akluxnails.com/api/public/sms-media/abc12"))))
                .thenReturn("MM123");
        MockMultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", new byte[]{1, 2, 3});

        var result = service.sendManualWithMedia(BUSINESS_ID, PHONE, "here's a photo", List.of(file));

        assertThat(result.sent()).isTrue();
        assertThat(reserved.getStatus()).isEqualTo("SENT");
        assertThat(reserved.getTwilioMessageSid()).isEqualTo("MM123");
        verify(messageLogService).save(reserved);
    }

    @Test
    @DisplayName("sendManualWithMedia: blocked number → skipped, nothing stored or sent")
    void sendManualWithMediaBlockedSkips() throws Exception {
        when(blockedNumberRepository.existsById(PHONE)).thenReturn(true);
        MockMultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", new byte[]{1});

        var result = service.sendManualWithMedia(BUSINESS_ID, PHONE, "hi", List.of(file));

        assertThat(result.sent()).isFalse();
        assertThat(result.reason()).isEqualTo("blocked");
        verifyNoInteractions(client, mediaService);
    }

    @Test
    @DisplayName("sendManualWithMedia: Twilio send failure → send_failed, reserved row updated, never throws")
    void sendManualWithMediaSendFailureReturnsSendFailed() throws Exception {
        when(configService.get(BUSINESS_ID)).thenReturn(configured());
        SmsMessage reserved = SmsMessage.builder().id(56L).build();
        when(messageLogService.logOutbound(eq(BUSINESS_ID), eq(null), eq(null), eq(PHONE), eq("photo"), eq(false), eq("pending"), eq(null)))
                .thenReturn(reserved);
        SmsMessageMedia media = SmsMessageMedia.builder().id(2L).smsMessageId(56L).accessToken("xyz99").build();
        when(mediaService.store(eq(56L), any(), any())).thenReturn(media);
        when(mediaService.publicUrl(media)).thenReturn("https://salon.akluxnails.com/api/public/sms-media/xyz99");
        doThrow(new java.io.IOException("boom")).when(client).send(any(), any(), any(), any());
        MockMultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", new byte[]{1});

        var result = service.sendManualWithMedia(BUSINESS_ID, PHONE, "photo", List.of(file));

        assertThat(result.sent()).isFalse();
        assertThat(result.reason()).isEqualTo("send_failed");
        assertThat(reserved.getStatus()).isEqualTo("NOT_SENT");
    }
}
