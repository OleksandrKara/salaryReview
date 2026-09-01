package com.salonreview.seo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salonreview.config.SeoCredentialCipher;
import com.salonreview.domain.SeoConnection;
import com.salonreview.repo.SeoConnectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SeoConnectionServiceTest {

    private static final String VALID_SA_JSON =
            "{\"client_email\":\"seo-monitoring@my-project.iam.gserviceaccount.com\",\"private_key\":\"fake-key\"}";

    private SeoConnectionRepository repo;
    private SeoCredentialCipher cipher;
    private SeoConnectionService service;

    @BeforeEach
    void setUp() {
        repo = mock(SeoConnectionRepository.class);
        cipher = mock(SeoCredentialCipher.class);
        service = new SeoConnectionService(repo, cipher, new ObjectMapper());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(cipher.encrypt(any())).thenAnswer(inv -> "enc:" + inv.getArgument(0));
    }

    @Test
    @DisplayName("first connect with no service-account JSON and no existing row fails loudly, saves nothing")
    void firstConnectRequiresServiceAccountJson() {
        when(repo.findByBusinessId(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.connect(1L, null, "552140452", "G-XXXX", "AIzaFakeKey123", 5L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("gscServiceAccountJson");

        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("first connect with no PageSpeed key and no existing row fails loudly, saves nothing")
    void firstConnectRequiresPagespeedKey() {
        when(repo.findByBusinessId(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.connect(1L, VALID_SA_JSON, "552140452", "G-XXXX", null, 5L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("pagespeedApiKey");

        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("malformed service-account JSON fails validation before saving")
    void malformedServiceAccountJsonRejected() {
        when(repo.findByBusinessId(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.connect(1L, "{not valid json", "552140452", "G-XXXX", "AIzaFakeKey123", 5L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not valid JSON");

        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("service-account JSON missing client_email/private_key fails validation before saving")
    void incompleteServiceAccountJsonRejected() {
        when(repo.findByBusinessId(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.connect(1L, "{\"project_id\":\"x\"}", "552140452", "G-XXXX",
                "AIzaFakeKey123", 5L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("client_email");

        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("successful first connect encrypts both secrets and saves the GA4 identifiers")
    void successfulConnectSavesEverything() {
        when(repo.findByBusinessId(1L)).thenReturn(Optional.empty());

        SeoConnection saved = service.connect(1L, VALID_SA_JSON, "552140452", "G-4087JHJSXE", "AIzaFakeKey123", 7L);

        assertThat(saved.getBusinessId()).isEqualTo(1L);
        assertThat(saved.getGscServiceAccountJsonEncrypted()).isEqualTo("enc:" + VALID_SA_JSON);
        assertThat(saved.getPagespeedApiKeyEncrypted()).isEqualTo("enc:AIzaFakeKey123");
        assertThat(saved.getGa4PropertyId()).isEqualTo("552140452");
        assertThat(saved.getGa4MeasurementId()).isEqualTo("G-4087JHJSXE");
        assertThat(saved.getConnectedByUserId()).isEqualTo(7L);
        assertThat(saved.getConnectedAt()).isNotNull();
    }

    @Test
    @DisplayName("reconnect with blank service-account JSON keeps the existing encrypted value, only updates GA4 fields")
    void reconnectWithoutNewServiceAccountKeepsExisting() {
        SeoConnection existing = SeoConnection.builder().id(9L).businessId(1L)
                .gscServiceAccountJsonEncrypted("enc:old-json")
                .pagespeedApiKeyEncrypted("enc:old-key")
                .ga4PropertyId("111").ga4MeasurementId("G-OLD").build();
        when(repo.findByBusinessId(1L)).thenReturn(Optional.of(existing));

        SeoConnection saved = service.connect(1L, null, "552140452", "G-4087JHJSXE", null, 7L);

        assertThat(saved.getGscServiceAccountJsonEncrypted()).isEqualTo("enc:old-json");
        assertThat(saved.getPagespeedApiKeyEncrypted()).isEqualTo("enc:old-key");
        assertThat(saved.getGa4PropertyId()).isEqualTo("552140452");
        assertThat(saved.getGa4MeasurementId()).isEqualTo("G-4087JHJSXE");
        verify(cipher, never()).encrypt(any());
    }

    @Test
    @DisplayName("serviceAccountEmail: null connection -> null; real connection -> extracted client_email")
    void serviceAccountEmailExtractsClientEmail() {
        assertThat(service.serviceAccountEmail(null)).isNull();

        SeoConnection connection = SeoConnection.builder().id(9L).businessId(1L)
                .gscServiceAccountJsonEncrypted("enc:sa-json").build();
        when(cipher.decrypt("enc:sa-json")).thenReturn(VALID_SA_JSON);

        assertThat(service.serviceAccountEmail(connection))
                .isEqualTo("seo-monitoring@my-project.iam.gserviceaccount.com");
    }

    @Test
    @DisplayName("maskedPagespeedApiKey: null connection -> null; real key -> masked to last 4 characters")
    void maskedPagespeedApiKeyMasksOnlyWhenPresent() {
        assertThat(service.maskedPagespeedApiKey(null)).isNull();

        SeoConnection connection = SeoConnection.builder().id(9L).businessId(1L)
                .pagespeedApiKeyEncrypted("enc:AIzaSyDqIMfAyG9ujLnRhnKHMEUsL44Wlb3xIL4").build();
        when(cipher.decrypt("enc:AIzaSyDqIMfAyG9ujLnRhnKHMEUsL44Wlb3xIL4"))
                .thenReturn("AIzaSyDqIMfAyG9ujLnRhnKHMEUsL44Wlb3xIL4");

        assertThat(service.maskedPagespeedApiKey(connection)).isEqualTo("••••xIL4");
    }
}
