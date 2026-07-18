package com.salonreview.sms;

import com.salonreview.domain.TwilioSmsConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class TwilioSmsServiceTest {

    private static final String TRANSACTIONAL_KEY = "test_transactional";
    private static final String MARKETING_KEY = "test_marketing";
    private static final String PHONE = "+15551234567";

    private SmsTemplateRegistry templateRegistry;
    private TwilioSmsConfigService configService;
    private SmsConsentRepository consentRepository;
    private TwilioSmsClient client;
    private TwilioSmsService service;

    @BeforeEach
    void setUp() {
        templateRegistry = mock(SmsTemplateRegistry.class);
        configService = mock(TwilioSmsConfigService.class);
        consentRepository = mock(SmsConsentRepository.class);
        client = mock(TwilioSmsClient.class);
        service = new TwilioSmsService(templateRegistry, configService, consentRepository, client);

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
        var result = service.sendTemplated("does_not_exist", PHONE, Map.of());

        assertThat(result.sent()).isFalse();
        assertThat(result.reason()).isEqualTo("unknown_template");
        verifyNoInteractions(consentRepository, client);
    }

    @Test
    @DisplayName("TRANSACTIONAL template sends regardless of consent")
    void transactionalSendsWithoutConsentCheck() throws Exception {
        when(configService.get()).thenReturn(configured());

        var result = service.sendTemplated(TRANSACTIONAL_KEY, PHONE, Map.of());

        assertThat(result.sent()).isTrue();
        verify(client).send(any(), eq(PHONE), eq("transactional body"));
        verifyNoInteractions(consentRepository);
    }

    @Test
    @DisplayName("MARKETING template blocked when consent is false")
    void marketingBlockedWithoutConsent() throws Exception {
        when(consentRepository.hasMarketingConsent(PHONE)).thenReturn(false);

        var result = service.sendTemplated(MARKETING_KEY, PHONE, Map.of());

        assertThat(result.sent()).isFalse();
        assertThat(result.reason()).isEqualTo("no_consent");
        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("MARKETING template sent when consent is true and credentials are configured")
    void marketingSentWithConsent() throws Exception {
        when(consentRepository.hasMarketingConsent(PHONE)).thenReturn(true);
        when(configService.get()).thenReturn(configured());

        var result = service.sendTemplated(MARKETING_KEY, PHONE, Map.of());

        assertThat(result.sent()).isTrue();
        verify(client).send(any(), eq(PHONE), eq("marketing body"));
    }

    @Test
    @DisplayName("unset credentials → not_configured, no HTTP attempt")
    void unconfiguredCredentialsSkipsSend() throws Exception {
        when(configService.get()).thenReturn(TwilioSmsConfig.builder().build());

        var result = service.sendTemplated(TRANSACTIONAL_KEY, PHONE, Map.of());

        assertThat(result.sent()).isFalse();
        assertThat(result.reason()).isEqualTo("not_configured");
        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("Twilio client failure → send_failed, never throws")
    void clientFailureReturnsSendFailed() throws Exception {
        when(configService.get()).thenReturn(configured());
        doThrow(new java.io.IOException("boom")).when(client).send(any(), any(), any());

        var result = service.sendTemplated(TRANSACTIONAL_KEY, PHONE, Map.of());

        assertThat(result.sent()).isFalse();
        assertThat(result.reason()).isEqualTo("send_failed");
    }
}
