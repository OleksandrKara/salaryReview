package com.salonreview.sms;

import com.salonreview.domain.SmsMessageMedia;
import com.salonreview.domain.TwilioSmsConfig;
import com.salonreview.repo.SmsMessageMediaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SmsMediaServiceTest {

    private static final String PUBLIC_BASE_URL = "https://salon.akluxnails.com";

    private SmsMessageMediaRepository repository;
    private TwilioSmsConfigService configService;
    private TwilioSmsClient client;
    private SmsMediaService service;

    @BeforeEach
    void setUp() {
        repository = mock(SmsMessageMediaRepository.class);
        configService = mock(TwilioSmsConfigService.class);
        client = mock(TwilioSmsClient.class);
        service = new SmsMediaService(repository, configService, client, PUBLIC_BASE_URL);
    }

    private static TwilioSmsConfig configured() {
        return TwilioSmsConfig.builder()
                .accountSid("AC123").apiKey("SK123").apiSecret("secret").fromPhoneNumber("+15559999999")
                .build();
    }

    @Test
    @DisplayName("publicUrl builds the /api/public/sms-media/{token} URL from the base URL")
    void publicUrlBuildsFromToken() {
        SmsMessageMedia media = SmsMessageMedia.builder().accessToken("abc12").build();

        assertThat(service.publicUrl(media)).isEqualTo("https://salon.akluxnails.com/api/public/sms-media/abc12");
    }

    @Test
    @DisplayName("store saves a row with a fresh, non-colliding access token")
    void storeGeneratesUniqueToken() {
        when(repository.existsByAccessToken(any())).thenReturn(false);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SmsMessageMedia saved = service.store(42L, "image/png", new byte[]{1, 2, 3});

        assertThat(saved.getSmsMessageId()).isEqualTo(42L);
        assertThat(saved.getContentType()).isEqualTo("image/png");
        assertThat(saved.getAccessToken()).hasSize(5);
    }

    @Test
    @DisplayName("store falls back to a generic content type when none is given")
    void storeDefaultsBlankContentType() {
        when(repository.existsByAccessToken(any())).thenReturn(false);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SmsMessageMedia saved = service.store(1L, null, new byte[]{1});

        assertThat(saved.getContentType()).isEqualTo("application/octet-stream");
    }

    @Test
    @DisplayName("mediaForMessages groups by message id")
    void mediaForMessagesGroupsByMessageId() {
        SmsMessageMedia m1 = SmsMessageMedia.builder().smsMessageId(1L).contentType("image/jpeg").accessToken("aaaaa").build();
        SmsMessageMedia m2 = SmsMessageMedia.builder().smsMessageId(1L).contentType("image/png").accessToken("bbbbb").build();
        when(repository.findBySmsMessageIdIn(List.of(1L))).thenReturn(List.of(m1, m2));

        Map<Long, List<SmsMediaService.MediaInfo>> result = service.mediaForMessages(List.of(1L));

        assertThat(result.get(1L)).hasSize(2);
        assertThat(result.get(1L).get(0).url()).isEqualTo("https://salon.akluxnails.com/api/public/sms-media/aaaaa");
    }

    @Test
    @DisplayName("mediaForMessages: empty input short-circuits with no repository query")
    void mediaForMessagesEmptyInputSkipsQuery() {
        assertThat(service.mediaForMessages(List.of())).isEmpty();
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("ingestInboundMedia: no NumMedia param → nothing fetched or stored")
    void ingestInboundMediaNoAttachmentsIsNoop() {
        service.ingestInboundMedia(1L, Map.of("Body", "hello"));

        verifyNoInteractions(configService, client, repository);
    }

    @Test
    @DisplayName("ingestInboundMedia: unconfigured credentials → skipped, nothing fetched")
    void ingestInboundMediaUnconfiguredSkips() {
        when(configService.getForAutomation()).thenReturn(TwilioSmsConfig.builder().build());

        service.ingestInboundMedia(1L, Map.of("NumMedia", "1", "MediaUrl0", "https://api.twilio.com/m0"));

        verifyNoInteractions(client, repository);
    }

    @Test
    @DisplayName("ingestInboundMedia: fetches and stores each attachment by index")
    void ingestInboundMediaFetchesAndStoresEach() throws Exception {
        when(configService.getForAutomation()).thenReturn(configured());
        when(client.fetchMedia(any(), eq("https://api.twilio.com/m0"))).thenReturn(new byte[]{1});
        when(client.fetchMedia(any(), eq("https://api.twilio.com/m1"))).thenReturn(new byte[]{2});
        when(repository.existsByAccessToken(any())).thenReturn(false);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.ingestInboundMedia(7L, Map.of(
                "NumMedia", "2",
                "MediaUrl0", "https://api.twilio.com/m0", "MediaContentType0", "image/jpeg",
                "MediaUrl1", "https://api.twilio.com/m1", "MediaContentType1", "image/png"));

        verify(client).fetchMedia(any(), eq("https://api.twilio.com/m0"));
        verify(client).fetchMedia(any(), eq("https://api.twilio.com/m1"));
        verify(repository, times(2)).save(any());
    }

    @Test
    @DisplayName("ingestInboundMedia: one attachment failing to fetch doesn't stop the others")
    void ingestInboundMediaOneFailureDoesNotStopOthers() throws Exception {
        when(configService.getForAutomation()).thenReturn(configured());
        when(client.fetchMedia(any(), eq("https://api.twilio.com/m0"))).thenThrow(new java.io.IOException("boom"));
        when(client.fetchMedia(any(), eq("https://api.twilio.com/m1"))).thenReturn(new byte[]{2});
        when(repository.existsByAccessToken(any())).thenReturn(false);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.ingestInboundMedia(7L, Map.of(
                "NumMedia", "2",
                "MediaUrl0", "https://api.twilio.com/m0",
                "MediaUrl1", "https://api.twilio.com/m1"));

        verify(repository, times(1)).save(any());
    }
}
