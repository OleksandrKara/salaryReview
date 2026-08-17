package com.salonreview.square;

import com.salonreview.config.SquareCredentialCipher;
import com.salonreview.config.SquareProperties;
import com.salonreview.domain.Business;
import com.salonreview.domain.SquareConnection;
import com.salonreview.repo.BusinessRepository;
import com.salonreview.repo.SquareConnectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SquareConnectionServiceTest {

    private SquareConnectionRepository repo;
    private SquareCredentialCipher cipher;
    private SquareClientProvider squareClientProvider;
    private BusinessRepository businesses;
    private SquareClient fakeClient;
    @SuppressWarnings("unchecked")
    private final Function<SquareProperties, SquareClient> clientFactory = mock(Function.class);
    private SquareConnectionService service;

    @BeforeEach
    void setUp() {
        repo = mock(SquareConnectionRepository.class);
        cipher = mock(SquareCredentialCipher.class);
        squareClientProvider = mock(SquareClientProvider.class);
        businesses = mock(BusinessRepository.class);
        fakeClient = mock(SquareClient.class);
        when(clientFactory.apply(any())).thenReturn(fakeClient);
        service = new SquareConnectionService(repo, cipher, squareClientProvider, businesses, clientFactory);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(cipher.encrypt(any())).thenAnswer(inv -> "enc:" + inv.getArgument(0));
    }

    @Test
    @DisplayName("first connect with no accessToken and no existing row fails loudly, saves nothing")
    void firstConnectRequiresAccessToken() {
        when(repo.findByBusinessId(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.connect(1L, SquareProperties.Environment.SANDBOX, null,
                "LOC1", null, 5L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("accessToken");

        verify(repo, never()).save(any());
        verifyNoInteractions(clientFactory);
    }

    @Test
    @DisplayName("Square rejects the credentials (throws) -> fails loudly, nothing is saved")
    void invalidCredentialsFailBeforeSaving() {
        when(repo.findByBusinessId(1L)).thenReturn(Optional.empty());
        when(fakeClient.location()).thenThrow(new RuntimeException("401 Unauthorized"));

        assertThatThrownBy(() -> service.connect(1L, SquareProperties.Environment.SANDBOX,
                "bad-token", "LOC1", null, 5L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Could not verify");

        verify(repo, never()).save(any());
        verify(businesses, never()).save(any());
        verify(squareClientProvider, never()).forget(any());
    }

    @Test
    @DisplayName("Square returns no location for the given id -> fails loudly, nothing is saved")
    void missingLocationFailsBeforeSaving() {
        when(repo.findByBusinessId(1L)).thenReturn(Optional.empty());
        when(fakeClient.location()).thenReturn(null);

        assertThatThrownBy(() -> service.connect(1L, SquareProperties.Environment.SANDBOX,
                "some-token", "WRONG_LOC", null, 5L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("no location");

        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("successful first connect encrypts the token, captures merchantId, syncs the business timezone, and busts the cached client")
    void successfulConnectSavesEverythingAndInvalidatesCache() {
        when(repo.findByBusinessId(1L)).thenReturn(Optional.empty());
        when(fakeClient.location()).thenReturn(
                new SquareClient.Location("LOC1", "AK PMU", "America/Los_Angeles", "USD", "MERCH123"));
        when(businesses.findById(1L)).thenReturn(Optional.of(
                Business.builder().id(1L).name("AK PMU").shortCode("annakarapmu").timezone("UTC").active(true).build()));

        SquareConnection saved = service.connect(1L, SquareProperties.Environment.PRODUCTION,
                "real-token-abc123", "LOC1", "sq0idp-xyz", 7L);

        assertThat(saved.getBusinessId()).isEqualTo(1L);
        assertThat(saved.getEnvironment()).isEqualTo(SquareProperties.Environment.PRODUCTION);
        assertThat(saved.getLocationId()).isEqualTo("LOC1");
        assertThat(saved.getApplicationId()).isEqualTo("sq0idp-xyz");
        assertThat(saved.getMerchantId()).isEqualTo("MERCH123");
        assertThat(saved.getAccessTokenEncrypted()).isEqualTo("enc:real-token-abc123");
        assertThat(saved.getConnectedByUserId()).isEqualTo(7L);
        assertThat(saved.getConnectedAt()).isNotNull();

        // Business timezone synced from Square's own location record.
        org.mockito.ArgumentCaptor<Business> businessCaptor = org.mockito.ArgumentCaptor.forClass(Business.class);
        verify(businesses).save(businessCaptor.capture());
        assertThat(businessCaptor.getValue().getTimezone()).isEqualTo("America/Los_Angeles");

        verify(squareClientProvider).forget(1L);
    }

    @Test
    @DisplayName("reconnect with a blank accessToken reuses the existing decrypted token, doesn't require a new one")
    void reconnectWithoutNewTokenReusesExisting() {
        SquareConnection existing = SquareConnection.builder().id(9L).businessId(1L)
                .environment(SquareProperties.Environment.SANDBOX).accessTokenEncrypted("enc:old-token")
                .locationId("LOC_OLD").build();
        when(repo.findByBusinessId(1L)).thenReturn(Optional.of(existing));
        when(cipher.decrypt("enc:old-token")).thenReturn("old-token");
        when(fakeClient.location()).thenReturn(
                new SquareClient.Location("LOC_NEW", "AK PMU", "America/Los_Angeles", "USD", "MERCH123"));
        when(businesses.findById(1L)).thenReturn(Optional.empty());

        service.connect(1L, SquareProperties.Environment.SANDBOX, null, "LOC_NEW", null, 7L);

        verify(cipher).decrypt("enc:old-token");
        verify(cipher).encrypt("old-token"); // re-encrypted (same plaintext) rather than left untouched
    }

    @Test
    @DisplayName("getWebhookSignatureKey: no square_connection row at all -> empty")
    void getWebhookSignatureKeyEmptyWhenNoConnection() {
        when(repo.findByBusinessId(1L)).thenReturn(Optional.empty());

        assertThat(service.getWebhookSignatureKey(1L)).isEmpty();
        verifyNoInteractions(cipher); // no decrypt attempt
    }

    @Test
    @DisplayName("getWebhookSignatureKey: connection exists but no webhook key configured -> empty")
    void getWebhookSignatureKeyEmptyWhenUnconfigured() {
        SquareConnection existing = SquareConnection.builder().id(9L).businessId(1L)
                .accessTokenEncrypted("enc:tok").webhookSignatureKeyEncrypted(null).build();
        when(repo.findByBusinessId(1L)).thenReturn(Optional.of(existing));

        assertThat(service.getWebhookSignatureKey(1L)).isEmpty();
        verify(cipher, never()).decrypt(any());
    }

    @Test
    @DisplayName("getWebhookSignatureKey: real key configured -> decrypts and returns it")
    void getWebhookSignatureKeyDecryptsWhenConfigured() {
        SquareConnection existing = SquareConnection.builder().id(9L).businessId(1L)
                .accessTokenEncrypted("enc:tok").webhookSignatureKeyEncrypted("enc:whsec_real123").build();
        when(repo.findByBusinessId(1L)).thenReturn(Optional.of(existing));
        when(cipher.decrypt("enc:whsec_real123")).thenReturn("whsec_real123");

        assertThat(service.getWebhookSignatureKey(1L)).contains("whsec_real123");
    }

    @Test
    @DisplayName("updateWebhookSignatureKey: no square_connection for the business -> fails loudly, nothing saved")
    void updateWebhookSignatureKeyRequiresExistingConnection() {
        when(repo.findByBusinessId(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateWebhookSignatureKey(1L, "whsec_new"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Connect Square");

        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("updateWebhookSignatureKey: null/blank key is a no-op, keeps the existing key unchanged")
    void updateWebhookSignatureKeyBlankIsNoOp() {
        SquareConnection existing = SquareConnection.builder().id(9L).businessId(1L)
                .accessTokenEncrypted("enc:tok").webhookSignatureKeyEncrypted("enc:whsec_old").build();
        when(repo.findByBusinessId(1L)).thenReturn(Optional.of(existing));

        SquareConnection resultNull = service.updateWebhookSignatureKey(1L, null);
        SquareConnection resultBlank = service.updateWebhookSignatureKey(1L, "   ");

        assertThat(resultNull.getWebhookSignatureKeyEncrypted()).isEqualTo("enc:whsec_old");
        assertThat(resultBlank.getWebhookSignatureKeyEncrypted()).isEqualTo("enc:whsec_old");
        verify(repo, never()).save(any());
        verify(cipher, never()).encrypt(any());
    }

    @Test
    @DisplayName("updateWebhookSignatureKey: real key provided -> encrypts and saves it")
    void updateWebhookSignatureKeyEncryptsAndSaves() {
        SquareConnection existing = SquareConnection.builder().id(9L).businessId(1L)
                .accessTokenEncrypted("enc:tok").webhookSignatureKeyEncrypted(null).build();
        when(repo.findByBusinessId(1L)).thenReturn(Optional.of(existing));

        SquareConnection saved = service.updateWebhookSignatureKey(1L, "whsec_brand_new");

        assertThat(saved.getWebhookSignatureKeyEncrypted()).isEqualTo("enc:whsec_brand_new");
        verify(repo).save(existing);
    }

    @Test
    @DisplayName("maskedWebhookSignatureKey: null connection or no key configured -> null; real key -> masked")
    void maskedWebhookSignatureKeyMasksOnlyWhenConfigured() {
        assertThat(service.maskedWebhookSignatureKey(null)).isNull();

        SquareConnection noKey = SquareConnection.builder().id(9L).businessId(1L)
                .accessTokenEncrypted("enc:tok").webhookSignatureKeyEncrypted(null).build();
        assertThat(service.maskedWebhookSignatureKey(noKey)).isNull();

        SquareConnection withKey = SquareConnection.builder().id(9L).businessId(1L)
                .accessTokenEncrypted("enc:tok").webhookSignatureKeyEncrypted("enc:whsec_abcd1234").build();
        when(cipher.decrypt("enc:whsec_abcd1234")).thenReturn("whsec_abcd1234");

        assertThat(service.maskedWebhookSignatureKey(withKey)).isEqualTo("••••1234");
    }
}
