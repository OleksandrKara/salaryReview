package com.salonreview.landing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salonreview.domain.Business;
import com.salonreview.repo.BusinessRepository;
import com.salonreview.sms.TwilioSmsService;
import com.salonreview.square.SquareClient;
import com.salonreview.square.SquareClientProvider;
import com.salonreview.telegram.ConsultationRequestNotification;
import com.salonreview.telegram.TelegramNotificationService;
import com.salonreview.web.GeoIpService;

/** POST /api/pmu/bookings/consultation against a real Postgres (rolled back); Square, Telegram and SMS are mocks. */
@SpringBootTest
@Transactional
class LandingPmuConsultationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 "
            + "(KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1";
    private static final String REAL_PHONE = "(619) 323-8888";
    private static final String ONLINE = "online-consultation";
    private static final String ANNA = LandingDefinitions.ANNA;

    @Autowired private JdbcTemplate jdbc;

    private SquareClient squareClient;
    private LandingSquareGateway gateway;
    private TelegramNotificationService telegram;
    private TwilioSmsService sms;
    private MockMvc mvc;
    private MockMvc disabled;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) c -> {
            ScriptUtils.executeSqlScript(c, new ClassPathResource("landing/marketing_schema.sql"));
            return null;
        });
        squareClient = mock(SquareClient.class);
        gateway = mock(LandingSquareGateway.class);
        telegram = mock(TelegramNotificationService.class);
        sms = mock(TwilioSmsService.class);
        SquareClientProvider provider = mock(SquareClientProvider.class);
        when(provider.forBusiness(any())).thenReturn(squareClient);
        BusinessRepository businesses = mock(BusinessRepository.class);
        Business pmu = mock(Business.class);
        when(pmu.getId()).thenReturn(2L);
        when(businesses.legacySmsBusiness()).thenReturn(pmu);
        when(pmu.getName()).thenReturn("PMU");
        when(pmu.getTimezone()).thenReturn("America/Los_Angeles");
        when(businesses.findByPublicDomain("book.pmu-annakara.com")).thenReturn(Optional.of(pmu));
        GeoIpService geoIp = mock(GeoIpService.class);
        when(geoIp.describe(any(), any())).thenReturn("US, San Diego (IP 203.0.113.7)");

        LandingWriteRepository repository = new LandingWriteRepository(jdbc);
        LandingAbuseGuard guard = new LandingAbuseGuard(repository, new TurnstileVerifier("", "http://127.0.0.1:1/unused"));
        LandingFourHandService fourHand = new LandingFourHandService(provider, telegram, sms, businesses);
        LandingPmuBookingService bookings = new LandingPmuBookingService(gateway, fourHand, telegram, sms, geoIp);
        mvc = MockMvcBuilders.standaloneSetup(new LandingWriteController(true, true, false, new LandingBusinessResolver(businesses),
                repository, guard, fourHand, bookings, null, null)).setControllerAdvice(new LandingWriteAdvice(org.mockito.Mockito.mock(LandingFailureAlerter.class))).build();
        disabled = MockMvcBuilders.standaloneSetup(new LandingWriteController(true, false, false,
                new LandingBusinessResolver(businesses), repository, guard, fourHand, bookings, null, null))
                .setControllerAdvice(new LandingWriteAdvice(org.mockito.Mockito.mock(LandingFailureAlerter.class))).build();

        jdbc.update("INSERT INTO marketing.landing_pages (id, name, slug, business_id) VALUES "
                + "('11111111-1111-1111-1111-111111111111', 'PMU', 'pmu', 2)");
        jdbc.update("INSERT INTO marketing.landing_variants (id, landing_page_id, name, business_id) VALUES "
                + "('22222222-2222-2222-2222-222222222222', '11111111-1111-1111-1111-111111111111', 'B', 2)");

        stubCatalog();
    }

    private void stubCatalog() throws Exception {
        for (LandingDefinitions.Consultation c : LandingDefinitions.CONSULTATIONS) {
            JsonNode item = JSON.readTree("{\"id\":\"" + c.itemId() + "\",\"item_data\":{\"variations\":[{\"id\":\""
                    + c.variationId() + "\",\"version\":1777,\"item_variation_data\":{\"service_duration\":1800000}}]}}");
            when(gateway.items(anyLong())).thenReturn(itemsMap(item));
        }
        when(gateway.team(anyLong())).thenReturn(Map.of(ANNA, JSON.readTree(
                "{\"id\":\"" + ANNA + "\",\"given_name\":\"Anna\",\"family_name\":\"Kara\"}")));
        when(gateway.location(anyLong())).thenReturn(JSON.readTree("{\"address\":{\"address_line_1\":\"1 Main St\","
                + "\"locality\":\"San Diego\",\"administrative_district_level_1\":\"CA\",\"postal_code\":\"92101\"}}"));
        when(squareClient.createLandingCustomer(any(), any(), any(), any(), any()))
                .thenReturn(new SquareClient.Customer("CUST-7", "Ann", "Test", null, "+16193238888", "ann@example.com", null));
    }

    private Map<String, JsonNode> itemsMap(JsonNode lastItem) throws Exception {
        Map<String, JsonNode> m = new LinkedHashMap<>();
        for (LandingDefinitions.Consultation c : LandingDefinitions.CONSULTATIONS) {
            m.put(c.itemId(), JSON.readTree("{\"id\":\"" + c.itemId() + "\",\"item_data\":{\"variations\":[{\"id\":\""
                    + c.variationId() + "\",\"version\":1777,\"item_variation_data\":{\"service_duration\":1800000}}]}}"));
        }
        return m;
    }

    private JsonNode booking() throws Exception {
        return JSON.readTree("{\"id\":\"BK-1\",\"status\":\"ACCEPTED\",\"start_at\":\"2026-12-05T18:30:00Z\"}");
    }

    private static MockHttpServletRequestBuilder json(String body) {
        return post("/api/pmu/bookings/consultation").header("User-Agent", UA)
                .header("X-Forwarded-For", "203.0.113.7").header("cf-ipcountry", "US")
                .header("Host", "book.pmu-annakara.com").contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static String body(String slug, String member, String phone, String extra) {
        return "{\"consultation_slug\":\"" + slug + "\",\"team_member_id\":\"" + member
                + "\",\"start_at\":\"2026-12-05T18:30:00Z\",\"customer\":{\"given_name\":\"Ann\",\"family_name\":\"Test\","
                + "\"email_address\":\"Ann@Example.com\",\"phone_number\":\"" + phone + "\",\"marketing_opt_in\":true},"
                + "\"note\":\"brows  please\",\"source_page_url\":\"https://pmu-annakara.com/brows/?x=1#top\","
                + "\"source_page_title\":\"Brows\",\"tracking\":{\"visitor_id\":\"" + UUID.randomUUID()
                + "\",\"landing_path\":\"/\",\"referrer\":\"https://www.google.com/\",\"utm_source\":\"google\","
                + "\"utm_medium\":\"cpc\",\"gclid\":\"abc\",\"utm_campaign\":\"brows-ads\",\"landing_page_id\":"
                + "\"11111111-1111-1111-1111-111111111111\",\"variant_id\":\"22222222-2222-2222-2222-222222222222\"}"
                + extra + "}";
    }

    private long count(String table, String where) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM marketing." + table + (where == null ? "" : " WHERE " + where),
                Long.class);
        return n == null ? 0 : n;
    }

    @Test
    void offUnlessConsultationFlagIsSet() throws Exception {
        disabled.perform(json(body(ONLINE, ANNA, REAL_PHONE, ""))).andExpect(status().isNotFound());
        verify(gateway, never()).createBooking(anyLong(), any(), any(), any(), any(), anyLong(), org.mockito.ArgumentMatchers.anyInt(), any(), any());
    }

    @Test
    void realConsultationBooksAndRunsEverySideEffect() throws Exception {
        when(gateway.createBooking(eq(2L), eq("CUST-7"), eq("2026-12-05T18:30:00Z"), eq(ANNA),
                eq(LandingDefinitions.CONSULTATIONS.get(0).variationId()), eq(1777L), eq(30), eq("brows  please"), any()))
                .thenReturn(booking());

        mvc.perform(json(body(ONLINE, ANNA, REAL_PHONE, ""))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.booking_id").value("BK-1")).andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.start_at").value("2026-12-05T18:30:00Z"))
                .andExpect(jsonPath("$.service_name").value("Online Consultation"))
                .andExpect(jsonPath("$.artist_name").value("Anna K."))
                .andExpect(jsonPath("$.square_customer_id").doesNotExist());

        verify(squareClient).setCustomerAttribute("CUST-7", "marketing_traffic_source", "Google Ads (google / cpc / brows-ads)");
        verify(squareClient).setCustomerAttribute("CUST-7", "sms_marketing_consent", "Opted In");
        verify(squareClient).setCustomerAttribute("CUST-7", "email_marketing_consent", "Opted In");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> vars = ArgumentCaptor.forClass(Map.class);
        verify(sms).sendTemplated(eq(2L), eq("consultation_request_confirmation"), eq(REAL_PHONE), vars.capture());
        assertThat(vars.getValue()).containsEntry("name", "Ann").containsEntry("businessName", "Anna Kara's PMU Studio");
        assertThat(vars.getValue().get("detailsClause")).startsWith("It's a free online consultation by phone: Anna K. "
                + "will call you at this number on Sat, Dec 5 at 10:30 AM PST, no need to come to the studio.");
        verify(gateway, never()).location(anyLong());

        ArgumentCaptor<ConsultationRequestNotification> alert = ArgumentCaptor.forClass(ConsultationRequestNotification.class);
        verify(telegram).sendConsultationRequestAlert(eq(2L), alert.capture());
        ConsultationRequestNotification n = alert.getValue();
        assertThat(n.online()).isTrue();
        assertThat(n.artistName()).isEqualTo("Anna K.");
        assertThat(n.sourcePageUrl()).isEqualTo("https://pmu-annakara.com/brows/");
        assertThat(n.adCampaign()).isEqualTo("brows-ads");
        assertThat(n.note()).isEqualTo("brows please");
        assertThat(n.location()).contains("San Diego");

        assertThat(jdbc.queryForMap("SELECT * FROM marketing.submissions WHERE square_booking_id = 'BK-1'"))
                .containsEntry("submission_type", "booking").containsEntry("service_name", "Online Consultation")
                .containsEntry("customer_phone", "+16193238888").containsEntry("landing_page_slug", "pmu");
        assertThat(((Number) jdbc.queryForObject("SELECT price FROM marketing.submissions WHERE square_booking_id = 'BK-1'",
                java.math.BigDecimal.class)).doubleValue()).isZero();
        assertThat(count("attribution", "booking_id = 'BK-1' AND source = 'google'")).isEqualTo(1);
        assertThat(count("sms_consent", "phone_number = '+16193238888' AND consented AND source = 'booking'")).isEqualTo(1);
        assertThat(count("email_consent", "email_address = 'Ann@example.com' AND consented AND source = 'booking'")).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT * FROM marketing.contacts WHERE phone_number = '+16193238888'"))
                .containsEntry("square_customer_id", "CUST-7").containsEntry("square_booking_id", "BK-1")
                .containsEntry("booking_status", "ACCEPTED").containsEntry("booking_artist_name", "Anna K.");
    }

    @Test
    void inPersonConsultationTextCarriesTheStudioAddress() throws Exception {
        when(gateway.createBooking(anyLong(), any(), any(), any(), any(), anyLong(), org.mockito.ArgumentMatchers.anyInt(), any(), any()))
                .thenReturn(booking());
        mvc.perform(json(body("in-person-consultation", ANNA, REAL_PHONE, ""))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.service_name").value("In-Person Consultation"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> vars = ArgumentCaptor.forClass(Map.class);
        verify(sms).sendTemplated(eq(2L), eq("consultation_request_confirmation"), eq(REAL_PHONE), vars.capture());
        assertThat(vars.getValue().get("detailsClause")).isEqualTo("It's an in-studio consultation at 1 Main St, San Diego, "
                + "CA 92101 on Sat, Dec 5 at 10:30 AM PST. Please arrive 5 minutes early. To help us prepare, you're welcome "
                + "to reply with 2-3 photos of the area you'd like us to work on.");
    }

    @Test
    void testPhoneGetsFakeConfirmationAndNothingIsWritten() throws Exception {
        mvc.perform(json(body(ONLINE, ANNA, "(619) 555-0142", ""))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.booking_id").value(org.hamcrest.Matchers.startsWith("TEST-")))
                .andExpect(jsonPath("$.status").value("TEST")).andExpect(jsonPath("$.start_at").value("2026-12-05T18:30:00Z"))
                .andExpect(jsonPath("$.service_name").value("Online Consultation"))
                .andExpect(jsonPath("$.artist_name").value("Anna K."));
        verify(gateway, never()).createBooking(anyLong(), any(), any(), any(), any(), anyLong(), org.mockito.ArgumentMatchers.anyInt(), any(), any());
        verify(squareClient, never()).createLandingCustomer(any(), any(), any(), any(), any());
        verify(sms, never()).sendTemplated(anyLong(), any(), any(), any());
        verify(telegram, never()).sendConsultationRequestAlert(anyLong(), any());
        assertThat(count("submissions", null)).isZero();
        assertThat(count("contacts", null)).isZero();
        assertThat(count("sms_consent", null)).isZero();
    }

    @Test
    void unknownSlugOrProviderAnswer400() throws Exception {
        mvc.perform(json(body("nope", ANNA, REAL_PHONE, ""))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Unknown consultation 'nope'"));
        mvc.perform(json(body(ONLINE, "TM-X", REAL_PHONE, ""))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("'TM-X' doesn't offer this consultation"));
        mvc.perform(json(body("nope", ANNA, "(619) 555-0142", ""))).andExpect(status().isBadRequest());
        verify(squareClient, never()).createLandingCustomer(any(), any(), any(), any(), any());
    }

    @Test
    void slotConflictAnswers409AndWritesNothing() throws Exception {
        when(gateway.createBooking(anyLong(), any(), any(), any(), any(), anyLong(), org.mockito.ArgumentMatchers.anyInt(), any(), any()))
                .thenThrow(new HttpClientErrorException(HttpStatus.BAD_REQUEST, "Bad Request", new HttpHeaders(),
                        "{\"errors\":[{\"code\":\"BOOKING_TIME_NOT_AVAILABLE\"}]}".getBytes(StandardCharsets.UTF_8),
                        StandardCharsets.UTF_8));
        mvc.perform(json(body(ONLINE, ANNA, REAL_PHONE, ""))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("This time slot is no longer available."));
        assertThat(count("submissions", null)).isZero();
        verify(sms, never()).sendTemplated(anyLong(), any(), any(), any());
        verify(telegram, never()).sendConsultationRequestAlert(anyLong(), any());
    }

    @Test
    void otherSquareErrorsAnswer502() throws Exception {
        when(gateway.createBooking(anyLong(), any(), any(), any(), any(), anyLong(), org.mockito.ArgumentMatchers.anyInt(), any(), any()))
                .thenThrow(new RuntimeException("timeout"));
        mvc.perform(json(body(ONLINE, ANNA, REAL_PHONE, ""))).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail").value("Unable to create appointment in Square"));
        assertThat(count("submissions", null)).isZero();
    }

    @Test
    void notificationFailuresNeverFailTheBooking() throws Exception {
        when(gateway.createBooking(anyLong(), any(), any(), any(), any(), anyLong(), org.mockito.ArgumentMatchers.anyInt(), any(), any()))
                .thenReturn(booking());
        when(sms.sendTemplated(anyLong(), any(), any(), any())).thenThrow(new RuntimeException("twilio down"));
        when(telegram.sendConsultationRequestAlert(anyLong(), any())).thenThrow(new RuntimeException("tg down"));
        mvc.perform(json(body(ONLINE, ANNA, REAL_PHONE, ""))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.booking_id").value("BK-1"));
        assertThat(count("submissions", "square_booking_id = 'BK-1'")).isEqualTo(1);
    }

    @Test
    void honeypotIsRejectedAndLoggedUnderTheConsultationEndpointName() throws Exception {
        mvc.perform(json(body(ONLINE, ANNA, REAL_PHONE, ",\"website\":\"http://spam\""))).andExpect(status().isBadRequest());
        assertThat(count("abuse_blocks", "reason = 'honeypot' AND endpoint = 'pmu_consultation'")).isEqualTo(1);
        verify(squareClient, never()).firstCustomerExact(any(), any());
    }

    @Test
    void invalidBodyAnswers422() throws Exception {
        mvc.perform(json("{\"consultation_slug\":\"" + ONLINE + "\"}")).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void sourcePageCleaningMatchesTheFormerService() {
        assertThat(LandingPmuBookingService.cleanSourceUrl("https://Book.PMU-annakara.com/a?b=1#c"))
                .isEqualTo("https://book.pmu-annakara.com/a");
        assertThat(LandingPmuBookingService.cleanSourceUrl("https://pmu-annakara.com")).isEqualTo("https://pmu-annakara.com/");
        assertThat(LandingPmuBookingService.cleanSourceUrl("http://pmu-annakara.com/")).isNull();
        assertThat(LandingPmuBookingService.cleanSourceUrl("https://evil.com/")).isNull();
        assertThat(LandingPmuBookingService.cleanOneLine("  a \n\t b  ", 120)).isEqualTo("a b");
        assertThat(LandingPmuBookingService.cleanOneLine("x".repeat(130), 120)).isEqualTo("x".repeat(119) + "…");
        assertThat(LandingPmuBookingService.cleanOneLine("   ", 120)).isNull();
    }
}
