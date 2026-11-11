package com.salonreview.landing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;

import com.salonreview.domain.Business;
import com.salonreview.repo.BusinessRepository;
import com.salonreview.sms.TwilioSmsService;
import com.salonreview.square.SquareClient;
import com.salonreview.square.SquareClientProvider;
import com.salonreview.telegram.FourHandRequestNotification;
import com.salonreview.telegram.TelegramNotificationService;

/** Write endpoints against a real Postgres (rolled back); Square, Telegram and SMS are mocks. */
@SpringBootTest
@Transactional
class LandingWriteControllerTest {

    private static final String MOBILE_UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 "
            + "(KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1";
    private static final String TEST_PHONE = "(619) 555-0101";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private javax.sql.DataSource dataSource;

    private SquareClient square;
    private TelegramNotificationService telegram;
    private TwilioSmsService sms;
    private MockMvc mvc;
    private LandingWriteRepository repository;
    private LandingAbuseGuard guard;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) c -> {
            ScriptUtils.executeSqlScript(c, new ClassPathResource("landing/marketing_schema.sql"));
            return null;
        });
        square = mock(SquareClient.class);
        telegram = mock(TelegramNotificationService.class);
        sms = mock(TwilioSmsService.class);
        SquareClientProvider provider = mock(SquareClientProvider.class);
        when(provider.forBusiness(any())).thenReturn(square);
        BusinessRepository businesses = mock(BusinessRepository.class);
        Business legacy = mock(Business.class);
        when(legacy.getId()).thenReturn(1L);
        when(legacy.getName()).thenReturn("AK Lux Nails");
        when(legacy.getTimezone()).thenReturn("America/Los_Angeles");
        when(businesses.legacySmsBusiness()).thenReturn(legacy);
        when(businesses.findByPublicDomain(any())).thenReturn(Optional.empty());
        repository = new LandingWriteRepository(jdbc);
        guard = new LandingAbuseGuard(repository, new TurnstileVerifier("", "http://127.0.0.1:1/unused"));
        this.businesses = businesses;
        this.provider = provider;
        seedLanding();
        mvc = controller(true);
    }

    private BusinessRepository businesses;
    private SquareClientProvider provider;

    private MockMvc controller(boolean enabled) {
        LandingWriteController c = new LandingWriteController(enabled, false, false, new LandingBusinessResolver(businesses),
                repository, guard, new LandingFourHandService(provider, telegram, sms, businesses), null, null, null);
        return MockMvcBuilders.standaloneSetup(c).setControllerAdvice(new LandingWriteAdvice(org.mockito.Mockito.mock(LandingFailureAlerter.class))).build();
    }

    private static MockHttpServletRequestBuilder json(String path, String body) {
        return post(path).header("User-Agent", MOBILE_UA).header("X-Forwarded-For", "203.0.113.7, 10.0.0.1")
                .header("Host", "mani.akluxnails.com").contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor replace(String name, String value) {
        return r -> {
            r.removeHeader(name);
            r.addHeader(name, value);
            return r;
        };
    }

    private void seedLanding() {
        jdbc.update("INSERT INTO marketing.landing_pages (id, name, slug, business_id) VALUES "
                + "('11111111-1111-1111-1111-111111111111', 'Mani', 'mani', 1)");
        jdbc.update("INSERT INTO marketing.landing_variants (id, landing_page_id, name, business_id) VALUES "
                + "('22222222-2222-2222-2222-222222222222', '11111111-1111-1111-1111-111111111111', 'B', 1)");
    }

    private long count(String table, String where, Object... args) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM marketing." + table + (where == null ? "" : " WHERE " + where),
                Long.class, args);
        return n == null ? 0 : n;
    }

    private static String tracking(String vid) {
        return "{\"visitor_id\":\"" + vid + "\",\"landing_path\":\"/\",\"referrer\":\"https://www.google.com/\","
                + "\"utm_source\":\"google\",\"utm_medium\":\"cpc\",\"gclid\":\"abc\"}";
    }

    @Test
    void disabledByDefaultAnswersNotFound() throws Exception {
        controller(false).perform(json("/api/tracking/visit", tracking(UUID.randomUUID().toString())))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.detail").value("Not Found"));
        assertThat(count("visits", null)).isZero();
    }

    @Test
    void visitRecordsRowAndSetsCookie() throws Exception {
        String vid = UUID.randomUUID().toString();
        MvcResult r = mvc.perform(json("/api/tracking/visit", tracking(vid))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.visitor_id").value(vid)).andReturn();
        assertThat(r.getResponse().getHeaders("Set-Cookie")).hasSize(1);
        assertThat(r.getResponse().getHeader("Set-Cookie")).startsWith("mani_vid=" + vid);
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM marketing.visits WHERE visitor_id = ?::uuid", vid);
        assertThat(row).containsEntry("device_type", "mobile").containsEntry("os_name", "iOS")
                .containsEntry("ip_address", "203.0.113.7").containsEntry("utm_source", "google")
                .containsEntry("gclid", "abc").containsEntry("business_id", 1L);
    }

    @Test
    void visitStoresEveryClickIdLandingUrlAndFirstVisitFlag() throws Exception {
        String vid = UUID.randomUUID().toString();
        String body = "{\"visitor_id\":\"" + vid + "\",\"landing_path\":\"/book/\",\"referrer\":\"https://pmu-annakara.com/\","
                + "\"utm_source\":\"google\",\"utm_medium\":\"cpc\",\"utm_campaign\":\"brows\",\"gclid\":\"g1\",\"gbraid\":\"gb1\","
                + "\"wbraid\":\"wb1\",\"msclkid\":\"ms1\",\"fbclid\":\"fb1\","
                + "\"landing_url\":\"/book/?utm_source=google&gclid=g1\",\"visit_referrer\":\"https://www.google.com/\",\"is_first_visit\":true}";
        mvc.perform(json("/api/tracking/visit", body)).andExpect(status().isCreated());
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM marketing.visits WHERE visitor_id = ?::uuid", vid);
        assertThat(row).containsEntry("gbraid", "gb1").containsEntry("wbraid", "wb1").containsEntry("msclkid", "ms1")
                .containsEntry("fbclid", "fb1").containsEntry("gclid", "g1").containsEntry("utm_campaign", "brows")
                .containsEntry("landing_url", "/book/?utm_source=google&gclid=g1")
                .containsEntry("visit_referrer", "https://www.google.com/").containsEntry("is_first_visit", true);

        String later = "{\"visitor_id\":\"" + vid + "\",\"landing_path\":\"/gallery/\",\"landing_url\":\"/gallery/\","
                + "\"visit_referrer\":\"https://pmu-annakara.com/book/\",\"is_first_visit\":false}";
        mvc.perform(json("/api/tracking/visit", later).header("Cookie", "mani_vid=" + vid)).andExpect(status().isCreated());
        assertThat(count("visits", "visitor_id = ?::uuid", vid)).isEqualTo(2);
        assertThat(count("visits", "visitor_id = ?::uuid AND is_first_visit", vid)).isEqualTo(1);
    }

    @Test
    void blankFirstVisitFlagAndMissingNewFieldsDefaultSafely() throws Exception {
        String vid = UUID.randomUUID().toString();
        mvc.perform(json("/api/tracking/visit", tracking(vid))).andExpect(status().isCreated());
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM marketing.visits WHERE visitor_id = ?::uuid", vid);
        assertThat(row.get("is_first_visit")).isEqualTo(false);
        assertThat(row.get("landing_url")).isNull();
        assertThat(row.get("gbraid")).isNull();
    }

    @Test
    void existingCookieWinsOverClientVisitorId() throws Exception {
        String cookieVid = UUID.randomUUID().toString();
        String clientVid = UUID.randomUUID().toString();
        MvcResult r = mvc.perform(json("/api/tracking/visit", tracking(clientVid)).header("Cookie", "mani_vid=" + cookieVid))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.visitor_id").value(cookieVid)).andReturn();
        assertThat(r.getResponse().getHeaders("Set-Cookie")).isEmpty();
        assertThat(count("visits", "visitor_id = ?::uuid", cookieVid)).isEqualTo(1);
        assertThat(count("visits", "visitor_id = ?::uuid", clientVid)).isZero();
    }

    @Test
    void botVisitGetsSuccessResponseButNoRow() throws Exception {
        String vid = UUID.randomUUID().toString();
        mvc.perform(json("/api/tracking/visit", tracking(vid)).with(replace("User-Agent", "curl/8.0")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.visitor_id").value(vid));
        assertThat(count("visits", null)).isZero();
    }

    @Test
    void eventAndFunnelEventAreRecorded() throws Exception {
        String vid = UUID.randomUUID().toString();
        String lp = "11111111-1111-1111-1111-111111111111";
        String variant = "22222222-2222-2222-2222-222222222222";
        MvcResult r = mvc.perform(json("/api/tracking/event", "{\"session_id\":\"" + vid + "\",\"landing_page_id\":\"" + lp
                + "\",\"variant_id\":\"" + variant + "\",\"event_type\":\"click\",\"metadata\":{\"button\":\"book\"}}"))
                .andExpect(status().isCreated()).andExpect(content().json("{\"recorded\":true}")).andReturn();
        assertThat(r.getResponse().getHeaders("Set-Cookie")).hasSize(2);
        assertThat(count("events", "session_id = ?::uuid AND event_type = 'click' AND metadata->>'button' = 'book'", vid))
                .isEqualTo(1);

        mvc.perform(json("/api/tracking/funnel-event", "{\"session_id\":\"" + vid + "\",\"flow_key\":\"mani\","
                + "\"step_key\":\"contact\",\"step_index\":0,\"step_count_total\":4}"))
                .andExpect(status().isCreated()).andExpect(content().json("{\"recorded\":true}"));
        assertThat(count("funnel_events", "session_id = ?::uuid AND step_key = 'contact' AND step_count_total = 4", vid))
                .isEqualTo(1);
    }

    @Test
    void invalidBodiesAnswer422WithDetailList() throws Exception {
        mvc.perform(json("/api/tracking/event", "{\"session_id\":\"s\",\"event_type\":\"nope\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.detail[0].loc[0]").value("body"));
        mvc.perform(json("/api/tracking/funnel-event", "{\"session_id\":\"s\",\"flow_key\":\"f\",\"step_key\":\"k\","
                + "\"step_index\":-1,\"step_count_total\":0}")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail.length()").value(2));
        mvc.perform(json("/api/tracking/visit", "not json")).andExpect(status().isUnprocessableEntity());
        mvc.perform(json("/api/contacts", "{\"given_name\":\"\",\"phone_number\":\"123\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.detail.length()").value(2));
        assertThat(count("events", null)).isZero();
    }

    @Test
    void contactCaptureUpsertsOneContactAndLogsStep1WithoutCreatingSquareCustomer() throws Exception {
        when(square.firstCustomerExact("phone_number", "+16195550101"))
                .thenReturn(new SquareClient.Customer("CUST-1", "Ann", null, null, "+16195550101", null, null));
        String body = "{\"given_name\":\"Ann\",\"phone_number\":\"" + TEST_PHONE + "\",\"email_address\":\"ann@example.com\","
                + "\"tracking\":" + tracking(UUID.randomUUID().toString()) + "}";
        mvc.perform(json("/api/contacts", body)).andExpect(status().isCreated()).andExpect(content().json("{\"recorded\":true}"));
        mvc.perform(json("/api/contacts", body)).andExpect(status().isCreated());

        assertThat(count("contacts", "phone_number = '+16195550101'")).isEqualTo(1);
        Map<String, Object> contact = jdbc.queryForMap("SELECT * FROM marketing.contacts WHERE phone_number = '+16195550101'");
        assertThat(contact).containsEntry("given_name", "Ann").containsEntry("square_customer_id", "CUST-1")
                .containsEntry("original_traffic_source", "Google Ads (google / cpc)")
                .containsEntry("device_type", "mobile");
        assertThat(count("submissions", "submission_type = 'step1' AND customer_phone = '+16195550101'")).isEqualTo(2);
        verify(square, never()).createLandingCustomer(any(), any(), any(), any(), any());
    }

    @Test
    void contactCaptureSurvivesSquareLookupFailure() throws Exception {
        when(square.firstCustomerExact(any(), any())).thenThrow(new RuntimeException("square down"));
        mvc.perform(json("/api/contacts", "{\"given_name\":\"Ann\",\"phone_number\":\"" + TEST_PHONE + "\"}"))
                .andExpect(status().isCreated());
        assertThat(count("contacts", "phone_number = '+16195550101' AND square_customer_id IS NULL")).isEqualTo(1);
    }

    private String fourHand(String phone, String extra, String vid) {
        return "{\"slot\":{\"start_at\":\"2026-12-05T18:30:00Z\",\"team_member_id\":\"TM1\",\"segments\":[{\"service_slug\":"
                + "\"four-hand-request\",\"service_variation_id\":\"V1\",\"service_variation_version\":1}]},"
                + "\"customer\":{\"given_name\":\"Ann\",\"family_name\":\"Test\",\"email_address\":\"Ann@Example.com\","
                + "\"phone_number\":\"" + phone + "\",\"marketing_opt_in\":true},\"requested_services\":\"Mani + Pedi\","
                + "\"note\":\"hi\",\"tracking\":" + tracking(vid).replace("}", ",\"landing_page_id\":\""
                + "11111111-1111-1111-1111-111111111111\",\"variant_id\":\"22222222-2222-2222-2222-222222222222\"}")
                + extra + "}";
    }

    @Test
    void fourHandRequestCreatesLeadWithAllSideEffects() throws Exception {
        when(square.createLandingCustomer(any(), any(), any(), any(), any()))
                .thenReturn(new SquareClient.Customer("CUST-9", "Ann", "Test", null, "+16195550101", "ann@example.com", null));
        String vid = UUID.randomUUID().toString();

        MvcResult r = mvc.perform(json("/api/bookings/four-hand-request", fourHand(TEST_PHONE, "", vid)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("requested"))
                .andExpect(jsonPath("$.service_name").value("4-Hand Service"))
                .andExpect(jsonPath("$.message").value(LandingFourHandService.CONFIRMATION_MESSAGE))
                .andExpect(jsonPath("$.booking_id").value(org.hamcrest.Matchers.startsWith("four-hand-req-")))
                .andExpect(jsonPath("$.square_customer_id").doesNotExist()).andReturn();
        String bookingId = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(r.getResponse().getContentAsString()).get("booking_id").asText();

        verify(square).createLandingCustomer(eq("Ann"), eq("Test"), eq(TEST_PHONE), eq("Ann@example.com"), any());
        verify(square).setCustomerAttribute("CUST-9", "marketing_traffic_source", "Google Ads (google / cpc)");
        verify(square).setCustomerAttribute("CUST-9", "utm_source", "google");
        verify(square).setCustomerAttribute("CUST-9", "utm_medium", "cpc");
        verify(square).setCustomerAttribute("CUST-9", "original_organic_source", "Google Ads (google / cpc)");
        verify(square).setCustomerAttribute("CUST-9", "sms_marketing_consent", "Opted In");
        verify(square).setCustomerAttribute("CUST-9", "email_marketing_consent", "Opted In");

        ArgumentCaptor<FourHandRequestNotification> alert = ArgumentCaptor.forClass(FourHandRequestNotification.class);
        verify(telegram).sendFourHandRequestAlert(eq(1L), alert.capture());
        assertThat(alert.getValue().source()).isEqualTo("mani");
        assertThat(alert.getValue().customerName()).isEqualTo("Ann Test");
        assertThat(alert.getValue().estimatedPrice()).isEqualTo(249.0);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> vars = ArgumentCaptor.forClass(Map.class);
        verify(sms).sendTemplated(eq(1L), eq("four_hand_request_received"), eq(TEST_PHONE), vars.capture());
        assertThat(vars.getValue()).containsEntry("name", "Ann").containsEntry("preferredTime", "Sat, Dec 5 at 10:30 AM PST");

        Map<String, Object> sub = jdbc.queryForMap("SELECT * FROM marketing.submissions WHERE square_booking_id = ?", bookingId);
        assertThat(sub).containsEntry("submission_type", "four_hand_request").containsEntry("service_name", "4-Hand Service")
                .containsEntry("customer_phone", "+16195550101").containsEntry("landing_page_slug", "mani")
                .containsEntry("variant_name", "B").containsEntry("customer_email", "Ann@example.com");
        assertThat(count("attribution", "booking_id = ? AND source = 'google'", bookingId)).isEqualTo(1);
        assertThat(count("sms_consent", "phone_number = '+16195550101' AND consented AND source = 'four_hand_request' "
                + "AND consent_text = 'Text me reminders & exclusive offers'")).isEqualTo(1);
        assertThat(count("email_consent", "email_address = 'Ann@example.com' AND consented")).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT * FROM marketing.contacts WHERE phone_number = '+16195550101'"))
                .containsEntry("booking_status", "requested").containsEntry("square_customer_id", "CUST-9")
                .containsEntry("square_booking_id", bookingId).containsEntry("booking_service_name", "4-Hand Service");
    }

    @Test
    void submissionKeepsItsOwnSourceAndClickIdsWhileFirstVisitStaysSeparate() throws Exception {
        when(square.createLandingCustomer(any(), any(), any(), any(), any()))
                .thenReturn(new SquareClient.Customer("CUST-8", "Ann", "Test", null, "+16195550101", "ann@example.com", null));
        String vid = UUID.randomUUID().toString();
        mvc.perform(json("/api/tracking/visit", "{\"visitor_id\":\"" + vid + "\",\"landing_path\":\"/\",\"fbclid\":\"first\","
                + "\"landing_url\":\"/?fbclid=first\",\"is_first_visit\":true}")).andExpect(status().isCreated());
        String body = fourHand(TEST_PHONE, "", vid).replace("\"gclid\":\"abc\"",
                "\"gclid\":\"abc\",\"gbraid\":\"gb9\",\"msclkid\":\"ms9\",\"landing_url\":\"/book/?gclid=abc\"");
        MvcResult r = mvc.perform(json("/api/bookings/four-hand-request", body.replace("\"visitor_id\":\"" + vid + "\"", "\"visitor_id\":\"" + vid + "\"")))
                .andExpect(status().isCreated()).andReturn();
        String bookingId = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(r.getResponse().getContentAsString()).get("booking_id").asText();
        Map<String, Object> sub = jdbc.queryForMap("SELECT * FROM marketing.submissions WHERE square_booking_id = ?", bookingId);
        assertThat(sub).containsEntry("gclid", "abc").containsEntry("gbraid", "gb9").containsEntry("msclkid", "ms9")
                .containsEntry("landing_url", "/book/?gclid=abc").containsEntry("traffic_source", "Google Ads (google / cpc)");
        assertThat(jdbc.queryForMap("SELECT fbclid, is_first_visit FROM marketing.visits WHERE visitor_id = ?::uuid", vid))
                .containsEntry("fbclid", "first").containsEntry("is_first_visit", true);
    }

    @Test
    void fourHandRequestReusesExistingSquareCustomerAndKeepsFirstOrganicSource() throws Exception {
        when(square.firstCustomerExact("phone_number", "+16195550101"))
                .thenReturn(new SquareClient.Customer("CUST-5", "Ann", "Test", null, "+16195550101", null, null));
        when(square.customerAttribute("CUST-5", "original_organic_source")).thenReturn("Instagram (organic)");
        mvc.perform(json("/api/bookings/four-hand-request", fourHand(TEST_PHONE, "", UUID.randomUUID().toString())))
                .andExpect(status().isCreated());
        verify(square, never()).createLandingCustomer(any(), any(), any(), any(), any());
        verify(square, never()).setCustomerAttribute(eq("CUST-5"), eq("original_organic_source"), any());
    }

    @Test
    void squareFailuresAnswer502AndWriteNothing() throws Exception {
        when(square.firstCustomerExact(any(), any())).thenThrow(new RuntimeException("boom"));
        mvc.perform(json("/api/bookings/four-hand-request", fourHand(TEST_PHONE, "", UUID.randomUUID().toString())))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail").value("Unable to look up customer record in Square"));

        org.mockito.Mockito.reset(square);
        when(square.firstCustomerExact(any(), any())).thenReturn(null);
        when(square.createLandingCustomer(any(), any(), any(), any(), any())).thenThrow(new RuntimeException("boom"));
        mvc.perform(json("/api/bookings/four-hand-request", fourHand(TEST_PHONE, "", UUID.randomUUID().toString())))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail").value("Unable to create customer record in Square"));
        assertThat(count("submissions", null)).isZero();
        verify(telegram, never()).sendFourHandRequestAlert(anyLong(), any());
        verify(sms, never()).sendTemplated(anyLong(), any(), any(), any());
    }

    @Test
    void honeypotIsRejectedBeforeSquareAndLogged() throws Exception {
        mvc.perform(json("/api/bookings/four-hand-request",
                fourHand("(619) 555-0102", ",\"website\":\"http://spam\"", UUID.randomUUID().toString())))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").isString());
        assertThat(count("abuse_blocks", "reason = 'honeypot' AND endpoint = 'four_hand_request'")).isEqualTo(1);
        verify(square, never()).firstCustomerExact(any(), any());
    }

    @Test
    void fourthRealPhoneSubmissionInADayIsRateLimitedButTestPhonesAreNot() throws Exception {
        when(square.firstCustomerExact(any(), any())).thenReturn(null);
        when(square.createLandingCustomer(any(), any(), any(), any(), any()))
                .thenReturn(new SquareClient.Customer("C", "A", "B", null, null, null, null));
        for (int i = 0; i < 3; i++) {
            mvc.perform(json("/api/bookings/four-hand-request", fourHand("(619) 323-9999", "", UUID.randomUUID().toString())
                    ).with(replace("X-Forwarded-For", "198.51.100." + i))).andExpect(status().isCreated());
        }
        mvc.perform(json("/api/bookings/four-hand-request", fourHand("(619) 323-9999", "", UUID.randomUUID().toString()))
                .with(replace("X-Forwarded-For", "198.51.100.77"))).andExpect(status().isBadRequest());
        assertThat(count("abuse_blocks", "reason = 'rate_limit_phone'")).isEqualTo(1);

        for (int i = 0; i < 4; i++) {
            mvc.perform(json("/api/bookings/four-hand-request", fourHand(TEST_PHONE, "", UUID.randomUUID().toString())))
                    .andExpect(status().isCreated());
        }
        verify(telegram, times(7)).sendFourHandRequestAlert(anyLong(), any());
    }

    @Test
    void blankTrackingAndMissingOptionalFieldsStillWork() throws Exception {
        when(square.createLandingCustomer(any(), any(), any(), any(), any()))
                .thenReturn(new SquareClient.Customer("C1", "A", "B", null, null, null, null));
        String body = "{\"slot\":{\"start_at\":\"2026-12-05T18:30:00Z\",\"team_member_id\":\"T\",\"segments\":[]},"
                + "\"customer\":{\"given_name\":\"A\",\"family_name\":\"B\",\"phone_number\":\"" + TEST_PHONE + "\"}}";
        mvc.perform(json("/api/bookings/four-hand-request", body)).andExpect(status().isCreated());
        verify(square).setCustomerAttribute("C1", "marketing_traffic_source", "Direct / No referrer");
        verify(square).setCustomerAttribute("C1", "original_organic_source", "na");
        verify(square).setCustomerAttribute("C1", "sms_marketing_consent", "Opted Out");
        verify(square, never()).setCustomerAttribute(eq("C1"), eq("email_marketing_consent"), any());
        assertThat(count("attribution", null)).isZero();
        assertThat(count("email_consent", null)).isZero();
        assertThat(count("sms_consent", "consented = false")).isEqualTo(1);
        assertThat(List.of(1)).isNotEmpty();
    }
}
