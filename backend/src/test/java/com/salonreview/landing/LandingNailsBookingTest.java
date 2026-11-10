package com.salonreview.landing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
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
import java.util.List;
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
import com.salonreview.repo.BusinessRepository;
import com.salonreview.sms.TwilioSmsService;
import com.salonreview.square.SquareClient;
import com.salonreview.square.SquareClientProvider;
import com.salonreview.telegram.TelegramNotificationService;
import com.salonreview.web.InternalNotificationController;

/** POST /api/bookings (nails) against a real Postgres (rolled back); Square, Telegram and SMS are mocks. */
@SpringBootTest
@Transactional
class LandingNailsBookingTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 "
            + "(KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1";
    private static final String PHONE = "(619) 323-8888";

    @Autowired private JdbcTemplate jdbc;

    private SquareClient squareClient;
    private LandingSquareGateway gateway;
    private InternalNotificationController notifications;
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
        notifications = mock(InternalNotificationController.class);
        SquareClientProvider provider = mock(SquareClientProvider.class);
        when(provider.forBusiness(any())).thenReturn(squareClient);
        BusinessRepository businesses = mock(BusinessRepository.class);
        when(businesses.findByPublicDomain("mani.akluxnails.com")).thenReturn(Optional.empty());

        LandingWriteRepository repository = new LandingWriteRepository(jdbc);
        LandingAbuseGuard guard = new LandingAbuseGuard(repository, new TurnstileVerifier("", "http://127.0.0.1:1/unused"));
        LandingFourHandService fourHand = new LandingFourHandService(provider, mock(TelegramNotificationService.class),
                mock(TwilioSmsService.class), businesses);
        LandingNailsBookingService nails = new LandingNailsBookingService(gateway, fourHand, new NailsLandingService(gateway));
        LandingWriteAdvice advice = new LandingWriteAdvice(mock(LandingFailureAlerter.class));
        mvc = MockMvcBuilders.standaloneSetup(new LandingWriteController(true, false, false, true,
                new LandingBusinessResolver(businesses), repository, guard, fourHand, null, null, notifications, nails))
                .setControllerAdvice(advice).build();
        disabled = MockMvcBuilders.standaloneSetup(new LandingWriteController(true, false, false, false,
                new LandingBusinessResolver(businesses), repository, guard, fourHand, null, null, notifications, nails))
                .setControllerAdvice(advice).build();

        jdbc.update("INSERT INTO marketing.landing_pages (id, name, slug, business_id) VALUES "
                + "('11111111-1111-1111-1111-111111111111', 'Mani', 'mani', 1)");
        jdbc.update("INSERT INTO marketing.landing_variants (id, landing_page_id, name, business_id) VALUES "
                + "('22222222-2222-2222-2222-222222222222', '11111111-1111-1111-1111-111111111111', 'B', 1)");

        when(gateway.items(anyLong())).thenReturn(catalog());
        when(gateway.team(anyLong())).thenReturn(Map.of(
                "T1", JSON.readTree("{\"id\":\"T1\",\"given_name\":\"Lucy\",\"family_name\":\"Smith\"}")));
        when(gateway.location(anyLong())).thenReturn(JSON.readTree("{\"name\":\"AK.LUX.NAILS Studio\",\"phone_number\":"
                + "\"+16193231185\",\"address\":{\"address_line_1\":\"1 Main St\",\"locality\":\"San Diego\","
                + "\"administrative_district_level_1\":\"CA\",\"postal_code\":\"92101\"}}"));
        when(gateway.bookingProfile(anyLong())).thenReturn(JSON.readTree(
                "{\"business_appointment_settings\":{\"cancellation_policy_text\":\"24h notice please\"}}"));
        when(squareClient.createLandingCustomer(any(), any(), any(), any(), any()))
                .thenReturn(new SquareClient.Customer("CUST-9", "Ann", "Test", null, "+16193238888", "ann@example.com", null));
        when(gateway.createBooking(anyLong(), any(), any(), any(), anyList(), any())).thenReturn(JSON.readTree(
                "{\"id\":\"BK-9\",\"status\":\"ACCEPTED\",\"start_at\":\"2026-12-05T18:30:00Z\"}"));
    }

    private static JsonNode variation(String id, String name, long cents, int minutes, String... team) {
        Map<String, Object> vd = new LinkedHashMap<>();
        vd.put("name", name);
        vd.put("price_money", Map.of("amount", cents, "currency", "USD"));
        vd.put("service_duration", minutes * 60_000L);
        vd.put("team_member_ids", List.of(team));
        return JSON.valueToTree(Map.of("id", id, "version", 1700000000001L, "item_variation_data", vd));
    }

    private static JsonNode item(String id, JsonNode... variations) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", "item " + id);
        data.put("variations", List.of(variations));
        return JSON.valueToTree(Map.of("id", id, "item_data", data));
    }

    private static Map<String, JsonNode> catalog() {
        Map<String, JsonNode> items = new LinkedHashMap<>();
        items.put("OPNE2Z2QTZR2USBD7AUJRRYF", item("OPNE2Z2QTZR2USBD7AUJRRYF",
                variation("M_TOP", "Top Nail Artist", 8500, 120, "T1")));
        items.put("OCMEURM3ONES53H4TCHRY5BS", item("OCMEURM3ONES53H4TCHRY5BS",
                variation("MR_TOP", "Top Nail Artist", 10000, 120, "T1")));
        items.put("QSJQCUYORMRDKQH4BTDUERIP", item("QSJQCUYORMRDKQH4BTDUERIP",
                variation("P_TOP", "Top Nail Artist", 9000, 60, "T1")));
        items.put("MS7R7N3RWRJUT3U6MT2GL5W2", item("MS7R7N3RWRJUT3U6MT2GL5W2",
                variation("PR_TOP", "Top Nail Artist", 11000, 60, "T1")));
        items.put("7RJJPUA5OTN5LL33J2CL3G6M", item("7RJJPUA5OTN5LL33J2CL3G6M",
                variation("D", "Design", 2000, 30, "T1")));
        items.put("HOQVAY5LVBH65RJHISMHLQVF", item("HOQVAY5LVBH65RJHISMHLQVF",
                variation("NUEKRALUQ5TSZZGP346BCL4Y", "Request", 0, 120, "T1")));
        return items;
    }

    private static MockHttpServletRequestBuilder json(String body) {
        return post("/api/bookings").header("User-Agent", UA).header("X-Forwarded-For", "203.0.113.7")
                .header("Host", "mani.akluxnails.com").contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static String body(String segments, String phone, String extra) {
        return "{\"slot\":{\"start_at\":\"2026-12-05T18:30:00Z\",\"team_member_id\":\"T1\",\"segments\":[" + segments + "]},"
                + "\"customer\":{\"given_name\":\"Ann\",\"family_name\":\"Test\",\"email_address\":\"Ann@Example.com\","
                + "\"phone_number\":\"" + phone + "\",\"marketing_opt_in\":true},\"note\":\"hello\",\"sms_opt_in\":true,"
                + "\"tracking\":{\"visitor_id\":\"" + UUID.randomUUID() + "\",\"landing_path\":\"/\",\"utm_source\":\"google\","
                + "\"utm_medium\":\"cpc\",\"utm_campaign\":\"mani-ads\",\"landing_page_id\":"
                + "\"11111111-1111-1111-1111-111111111111\",\"variant_id\":\"22222222-2222-2222-2222-222222222222\"}"
                + extra + "}";
    }

    private static String seg(String slug, String variation) {
        return "{\"service_slug\":\"" + slug + "\",\"service_variation_id\":\"" + variation
                + "\",\"service_variation_version\":1}";
    }

    private long count(String table, String where) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM marketing." + table + (where == null ? "" : " WHERE " + where),
                Long.class);
        return n == null ? 0 : n;
    }

    @Test
    void offUnlessNailsFlagIsSet() throws Exception {
        disabled.perform(json(body(seg("manicure", "M_TOP"), PHONE, ""))).andExpect(status().isNotFound());
        verify(gateway, never()).createBooking(anyLong(), any(), any(), any(), anyList(), any());
    }

    @Test
    void manicureWithDesignBooksOneAppointmentAndRunsEverySideEffect() throws Exception {
        mvc.perform(json(body(seg("manicure", "M_TOP") + "," + seg("design", "D"), PHONE, ""))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.booking_id").value("BK-9")).andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.start_at").value("2026-12-05T18:30:00Z"))
                .andExpect(jsonPath("$.duration_minutes").value(150))
                .andExpect(jsonPath("$.service_name").value("Russian Hard Gel Manicure + Nail Art / Design"))
                .andExpect(jsonPath("$.tier").value("top")).andExpect(jsonPath("$.artist_name").value("Lucy S."))
                .andExpect(jsonPath("$.price").value(105.0)).andExpect(jsonPath("$.compare_at_price").value(120.0))
                .andExpect(jsonPath("$.location_name").value("AK.LUX.NAILS Studio"))
                .andExpect(jsonPath("$.location_address").value("1 Main St, San Diego, CA 92101"))
                .andExpect(jsonPath("$.location_phone").value("+16193231185"))
                .andExpect(jsonPath("$.cancellation_policy_text").value("24h notice please"))
                .andExpect(jsonPath("$.square_customer_id").doesNotExist());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SquareClient.LandingSegment>> segs = ArgumentCaptor.forClass(List.class);
        verify(gateway).createBooking(eq(1L), eq("CUST-9"), eq("2026-12-05T18:30:00Z"), eq("T1"), segs.capture(), eq("hello"));
        assertThat(segs.getValue()).containsExactly(new SquareClient.LandingSegment("M_TOP", 1700000000001L, 120),
                new SquareClient.LandingSegment("D", 1700000000001L, 30));

        verify(squareClient).setCustomerAttribute("CUST-9", "sms_marketing_consent", "Opted In");
        verify(squareClient).setCustomerAttribute("CUST-9", "email_marketing_consent", "Opted In");

        assertThat(jdbc.queryForMap("SELECT * FROM marketing.submissions WHERE square_booking_id = 'BK-9'"))
                .containsEntry("submission_type", "booking").containsEntry("customer_phone", "+16193238888")
                .containsEntry("landing_page_slug", "mani");
        assertThat(count("attribution", "booking_id = 'BK-9' AND source = 'google'")).isEqualTo(1);
        assertThat(count("sms_consent", "phone_number = '+16193238888' AND consented AND source = 'booking'")).isEqualTo(1);
        assertThat(count("email_consent", "email_address = 'Ann@example.com' AND consented AND source = 'booking'")).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT * FROM marketing.contacts WHERE phone_number = '+16193238888'"))
                .containsEntry("square_customer_id", "CUST-9").containsEntry("square_booking_id", "BK-9")
                .containsEntry("booking_status", "ACCEPTED").containsEntry("booking_artist_name", "Lucy S.");
    }

    @Test
    void promoIsEnrolledAfterTheBooking() throws Exception {
        mvc.perform(json(body(seg("pedicure", "P_TOP"), PHONE,
                ",\"promo\":{\"code\":\"VIP10\",\"exp_epoch_seconds\":1900000000,\"signature\":\"sig\"}")))
                .andExpect(status().isCreated());
        ArgumentCaptor<InternalNotificationController.RebookingPromoEnrollRequest> req =
                ArgumentCaptor.forClass(InternalNotificationController.RebookingPromoEnrollRequest.class);
        verify(notifications).enrollRebookingPromo(req.capture());
        assertThat(req.getValue().squareCustomerId()).isEqualTo("CUST-9");
        assertThat(req.getValue().businessId()).isEqualTo(1L);
    }

    @Test
    void unknownServiceIsA400BeforeAnyCustomerOrBookingIsCreated() throws Exception {
        mvc.perform(json(body(seg("manicure", "WRONG"), PHONE, ""))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("'manicure' pricing no longer matches variation 'WRONG'."));
        mvc.perform(json(body(seg("nope", "M_TOP"), PHONE, ""))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Unknown service 'nope' with variation 'M_TOP'."));
        mvc.perform(json(body("", PHONE, ""))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("No services selected."));
        verify(squareClient, never()).createLandingCustomer(any(), any(), any(), any(), any());
        verify(gateway, never()).createBooking(anyLong(), any(), any(), any(), anyList(), any());
    }

    @Test
    void takenSlotIs409AndOtherSquareFailuresAre502() throws Exception {
        when(gateway.createBooking(anyLong(), any(), any(), any(), anyList(), any())).thenThrow(
                HttpClientErrorException.create(HttpStatus.CONFLICT, "Conflict", HttpHeaders.EMPTY,
                        "{\"errors\":[{\"code\":\"BOOKING_TIME_NOT_AVAILABLE\"}]}".getBytes(StandardCharsets.UTF_8), null));
        mvc.perform(json(body(seg("manicure", "M_TOP"), PHONE, ""))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("This time slot is no longer available."));
        org.mockito.Mockito.doThrow(
                HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad", HttpHeaders.EMPTY,
                        "{\"errors\":[{\"code\":\"INVALID_VALUE\"}]}".getBytes(StandardCharsets.UTF_8), null))
                .when(gateway).createBooking(anyLong(), any(), any(), any(), anyList(), any());
        mvc.perform(json(body(seg("manicure", "M_TOP"), PHONE, ""))).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail").value("Unable to create appointment in Square"));
        assertThat(count("submissions", null)).isZero();
    }

    @Test
    void honeypotIsRejectedWithoutBooking() throws Exception {
        mvc.perform(json(body(seg("manicure", "M_TOP"), PHONE, ",\"website\":\"http://spam\""))).andExpect(status().isBadRequest());
        verify(gateway, never()).createBooking(anyLong(), any(), any(), any(), anyList(), any());
    }
}
