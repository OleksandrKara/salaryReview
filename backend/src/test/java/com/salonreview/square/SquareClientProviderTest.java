package com.salonreview.square;

import com.salonreview.config.SquareCredentialCipher;
import com.salonreview.config.SquareProperties;
import com.salonreview.domain.SquareConnection;
import com.salonreview.repo.SquareConnectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SquareClientProviderTest {

    private SquareConnectionRepository connections;
    private SquareCredentialCipher cipher;
    private SquareClientProvider provider;

    @BeforeEach
    void setUp() {
        connections = mock(SquareConnectionRepository.class);
        cipher = mock(SquareCredentialCipher.class);
        provider = new SquareClientProvider(connections, cipher);
    }

    @Test
    @DisplayName("builds a SquareClient by decrypting the stored connection's token")
    void buildsClientFromStoredConnection() {
        when(connections.findByBusinessId(1L)).thenReturn(Optional.of(SquareConnection.builder()
                .businessId(1L).environment(SquareProperties.Environment.SANDBOX)
                .accessTokenEncrypted("cipher-text").locationId("L1").build()));
        when(cipher.decrypt("cipher-text")).thenReturn("plain-token");

        SquareClient client = provider.forBusiness(1L);

        assertThat(client).isNotNull();
        verify(cipher).decrypt("cipher-text");
    }

    @Test
    @DisplayName("caches the constructed client — a second call within the TTL doesn't hit the repo again")
    void cachesConstructedClient() {
        when(connections.findByBusinessId(1L)).thenReturn(Optional.of(SquareConnection.builder()
                .businessId(1L).environment(SquareProperties.Environment.SANDBOX)
                .accessTokenEncrypted("cipher-text").locationId("L1").build()));
        when(cipher.decrypt("cipher-text")).thenReturn("plain-token");

        SquareClient first = provider.forBusiness(1L);
        SquareClient second = provider.forBusiness(1L);

        assertThat(first).isSameAs(second);
        verify(connections, times(1)).findByBusinessId(1L);
    }

    @Test
    @DisplayName("two businesses get two independent client instances, never sharing state")
    void differentBusinessesGetDifferentClients() {
        when(connections.findByBusinessId(1L)).thenReturn(Optional.of(SquareConnection.builder()
                .businessId(1L).environment(SquareProperties.Environment.SANDBOX)
                .accessTokenEncrypted("cipher-a").locationId("LA").build()));
        when(connections.findByBusinessId(2L)).thenReturn(Optional.of(SquareConnection.builder()
                .businessId(2L).environment(SquareProperties.Environment.SANDBOX)
                .accessTokenEncrypted("cipher-b").locationId("LB").build()));
        when(cipher.decrypt(eq("cipher-a"))).thenReturn("token-a");
        when(cipher.decrypt(eq("cipher-b"))).thenReturn("token-b");

        SquareClient clientA = provider.forBusiness(1L);
        SquareClient clientB = provider.forBusiness(2L);

        assertThat(clientA).isNotSameAs(clientB);
    }

    @Test
    @DisplayName("forget() forces the next call to rebuild from the database")
    void forgetForcesRebuild() {
        when(connections.findByBusinessId(1L)).thenReturn(Optional.of(SquareConnection.builder()
                .businessId(1L).environment(SquareProperties.Environment.SANDBOX)
                .accessTokenEncrypted("cipher-text").locationId("L1").build()));
        when(cipher.decrypt("cipher-text")).thenReturn("plain-token");
        provider.forBusiness(1L);

        provider.forget(1L);
        provider.forBusiness(1L);

        verify(connections, times(2)).findByBusinessId(1L);
    }

    @Test
    @DisplayName("no connection configured for a business fails loudly, not with a null/default client")
    void missingConnectionFailsLoudly() {
        when(connections.findByBusinessId(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> provider.forBusiness(99L))
                .isInstanceOf(com.salonreview.config.BusinessSetupIncompleteException.class)
                .satisfies(ex -> assertThat(((com.salonreview.config.BusinessSetupIncompleteException) ex).getCode())
                        .isEqualTo("square_not_connected"));
    }
}
