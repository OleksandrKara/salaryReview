package com.salonreview.web;

import com.salonreview.config.InternalApiProperties;
import com.salonreview.config.SquareProperties;
import com.salonreview.domain.Business;
import com.salonreview.repo.BusinessRepository;
import com.salonreview.square.SquareConnectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone {@code MockMvc} — same reasoning as {@link InternalNotificationControllerTest}: auth
 * here is the controller's own {@code X-Internal-Api-Key} check, not a session.
 */
class InternalBusinessControllerTest {

    private InternalApiProperties props;
    private BusinessRepository businesses;
    private SquareConnectionService squareConnections;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        props = mock(InternalApiProperties.class);
        businesses = mock(BusinessRepository.class);
        squareConnections = mock(SquareConnectionService.class);
        InternalBusinessController controller = new InternalBusinessController(props, businesses, squareConnections);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("by-domain: missing key header → 401")
    void byDomainMissingKeyReturns401() throws Exception {
        when(props.getKey()).thenReturn("secret");

        mvc.perform(get("/api/internal/businesses/by-domain").param("domain", "mani.akluxnails.com"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("by-domain: wrong key header → 401")
    void byDomainWrongKeyReturns401() throws Exception {
        when(props.getKey()).thenReturn("secret");

        mvc.perform(get("/api/internal/businesses/by-domain")
                        .header("X-Internal-Api-Key", "wrong")
                        .param("domain", "mani.akluxnails.com"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("by-domain: unknown domain → 404")
    void byDomainUnknownDomainReturns404() throws Exception {
        when(props.getKey()).thenReturn("secret");
        when(businesses.findByPublicDomain("nowhere.example.com")).thenReturn(Optional.empty());

        mvc.perform(get("/api/internal/businesses/by-domain")
                        .header("X-Internal-Api-Key", "secret")
                        .param("domain", "nowhere.example.com"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("by-domain: known domain resolves to its business")
    void byDomainKnownDomainResolves() throws Exception {
        when(props.getKey()).thenReturn("secret");
        Business business = Business.builder().id(2L).name("AK PMU").shortCode("akpmu")
                .timezone("America/Los_Angeles").active(true).publicDomain("akpmu.example.com").build();
        when(businesses.findByPublicDomain("akpmu.example.com")).thenReturn(Optional.of(business));

        mvc.perform(get("/api/internal/businesses/by-domain")
                        .header("X-Internal-Api-Key", "secret")
                        .param("domain", "akpmu.example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessId").value(2))
                .andExpect(jsonPath("$.name").value("AK PMU"))
                .andExpect(jsonPath("$.timezone").value("America/Los_Angeles"));
    }

    @Test
    @DisplayName("square-credentials: missing key header → 401")
    void credentialsMissingKeyReturns401() throws Exception {
        when(props.getKey()).thenReturn("secret");

        mvc.perform(get("/api/internal/businesses/2/square-credentials"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("square-credentials: business not connected to Square → 404")
    void credentialsNotConnectedReturns404() throws Exception {
        when(props.getKey()).thenReturn("secret");
        when(squareConnections.plainCredentials(2L)).thenReturn(Optional.empty());

        mvc.perform(get("/api/internal/businesses/2/square-credentials")
                        .header("X-Internal-Api-Key", "secret"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("square-credentials: connected business returns decrypted token/location/environment")
    void credentialsReturnsDecryptedValues() throws Exception {
        when(props.getKey()).thenReturn("secret");
        when(squareConnections.plainCredentials(2L)).thenReturn(Optional.of(
                new SquareConnectionService.PlainCredentials("sq0atp-real-token", "LOC123",
                        SquareProperties.Environment.PRODUCTION)));

        mvc.perform(get("/api/internal/businesses/2/square-credentials")
                        .header("X-Internal-Api-Key", "secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("sq0atp-real-token"))
                .andExpect(jsonPath("$.locationId").value("LOC123"))
                .andExpect(jsonPath("$.environment").value("PRODUCTION"));
    }
}
