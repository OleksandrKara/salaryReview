package com.salonreview.landing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
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
import com.salonreview.sms.ConsultationOfferService;
import com.salonreview.telegram.PaymentFailedNotification;
import com.salonreview.web.InternalNotificationController;
import com.salonreview.telegram.TelegramNotificationService;
import com.salonreview.web.GeoIpService;

/** POST /api/pmu/bookings/deposit and /service against a real Postgres (rolled back); Square, Telegram, SMS are mocks. */
@SpringBootTest
@Transactional
class LandingPmuPaymentTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 "
            + "(KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1";
    private static final String REAL_PHONE = "(619) 323-8888";
    private static final String ANNA = LandingDefinitions.ANNA;
    private static final String VAR = "VAR-PMU-1";
    private static final LandingDefinitions.Technique TECH = LandingDefinitions.TECHNIQUES.get(0);

    @Autowired private JdbcTemplate jdbc;

    private SquareClient squareClient;
    private LandingSquareGateway gateway;
    private TelegramNotificationService telegram;
    private PmuLandingService menu;
    private ConsultationOfferService offers;
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
        telegram = mock(TelegramNotificationService.class);
        menu = mock(PmuLandingService.class);
        offers = mock(ConsultationOfferService.class);
        notifications = mock(InternalNotificationController.class);
        TwilioSmsService sms = mock(TwilioSmsService.class);
        SquareClientProvider provider = mock(SquareClientProvider.class);
        when(provider.forBusiness(any())).thenReturn(squareClient);
        BusinessRepository businesses = mock(BusinessRepository.class);
        Business pmu = mock(Business.class);
        when(pmu.getId()).thenReturn(2L);
        when(businesses.legacySmsBusiness()).thenReturn(pmu);
        when(pmu.getName()).thenReturn("PMU");
        when(pmu.getTimezone()).thenReturn("America/Los_Angeles");
        when(businesses.findByPublicDomain("book.pmu-annakara.com")).thenReturn(Optional.of(pmu));

        LandingWriteRepository repository = new LandingWriteRepository(jdbc);
        LandingAbuseGuard guard = new LandingAbuseGuard(repository, new TurnstileVerifier("", "http://127.0.0.1:1/unused"));
        LandingFourHandService fourHand = new LandingFourHandService(provider, telegram, sms, businesses);
        LandingPmuPaymentService payments = new LandingPmuPaymentService(gateway, menu, fourHand, telegram, offers);
        mvc = MockMvcBuilders.standaloneSetup(new LandingWriteController(true, true, true, new LandingBusinessResolver(businesses),
                repository, guard, fourHand, null, payments, notifications)).setControllerAdvice(new LandingWriteAdvice()).build();
        disabled = MockMvcBuilders.standaloneSetup(new LandingWriteController(true, true, false,
                new LandingBusinessResolver(businesses), repository, guard, fourHand, null, payments, notifications))
                .setControllerAdvice(new LandingWriteAdvice()).build();

        jdbc.update("INSERT INTO marketing.landing_pages (id, name, slug, business_id) VALUES "
                + "('11111111-1111-1111-1111-111111111111', 'PMU', 'pmu', 2)");
        jdbc.update("INSERT INTO marketing.landing_variants (id, landing_page_id, name, business_id) VALUES "
                + "('22222222-2222-2222-2222-222222222222', '11111111-1111-1111-1111-111111111111', 'B', 2)");

        when(gateway.items(anyLong())).thenReturn(Map.of(TECH.itemId(), JSON.readTree("{\"id\":\"" + TECH.itemId()
                + "\",\"item_data\":{\"variations\":[{\"id\":\"" + TECH.variationId() + "\",\"version\":55,"
                + "\"item_variation_data\":{\"service_duration\":7200000,\"price_money\":{\"amount\":45000}}}]}}")));
        when(gateway.team(anyLong())).thenReturn(Map.of(ANNA, JSON.readTree(
                "{\"id\":\"" + ANNA + "\",\"given_name\":\"Anna\",\"family_name\":\"Kara\"}")));
        when(squareClient.createLandingCustomer(any(), any(), any(), any(), any()))
                .thenReturn(new SquareClient.Customer("CUST-7", "Ann", "Test", null, "+16193238888", "ann@example.com", null));
        when(menu.findOption(anyLong(), eq(VAR))).thenReturn(Optional.of(new PmuLandingService.FoundOption(
                "Lip Blush", VAR, 9L, java.util.List.of(ANNA), 450.0, 120, 100.0)));
        when(menu.findOption(anyLong(), eq("VAR-FREE"))).thenReturn(Optional.of(new PmuLandingService.FoundOption(
                "Brow Wax", "VAR-FREE", 3L, java.util.List.of(ANNA), 60.0, 30, 0.0)));
    }

    private JsonNode booking() throws Exception {
        return JSON.readTree("{\"id\":\"BK-1\",\"status\":\"ACCEPTED\",\"start_at\":\"2026-12-05T18:30:00Z\"}");
    }

    private JsonNode payment() throws Exception {
        return JSON.readTree("{\"id\":\"PAY-1\",\"status\":\"COMPLETED\"}");
    }

    private static MockHttpServletRequestBuilder json(String path, String body) {
        return post(path).header("User-Agent", UA).header("X-Forwarded-For", "203.0.113.7")
                .header("Host", "book.pmu-annakara.com").contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static String customer(String phone) {
        return "\"customer\":{\"given_name\":\"Ann\",\"family_name\":\"Test\",\"email_address\":\"Ann@Example.com\","
                + "\"phone_number\":\"" + phone + "\",\"marketing_opt_in\":true}";
    }

    private static String tracking() {
        return ",\"tracking\":{\"visitor_id\":\"" + UUID.randomUUID() + "\",\"utm_source\":\"google\","
                + "\"utm_medium\":\"cpc\",\"utm_campaign\":\"lips\",\"landing_page_id\":"
                + "\"11111111-1111-1111-1111-111111111111\",\"variant_id\":\"22222222-2222-2222-2222-222222222222\"}";
    }

    private static String deposit(String phone, String extra) {
        return "{\"technique_slug\":\"" + TECH.slug() + "\",\"team_member_id\":\"" + ANNA
                + "\",\"start_at\":\"2026-12-05T18:30:00Z\"," + customer(phone) + ",\"source_id\":\"cnon:card-nonce-ok\""
                + tracking() + extra + "}";
    }

    private static String service(String variation, String phone, String extra) {
        return "{\"variation_id\":\"" + variation + "\",\"team_member_id\":\"" + ANNA
                + "\",\"start_at\":\"2026-12-05T18:30:00Z\"," + customer(phone) + tracking() + extra + "}";
    }

    private static final String DEP = "/api/pmu/bookings/deposit";
    private static final String SVC = "/api/pmu/bookings/service";

    private long count(String table, String where) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM marketing." + table + (where == null ? "" : " WHERE " + where),
                Long.class);
        return n == null ? 0 : n;
    }

    private void stubBookingAndPayment() throws Exception {
        when(gateway.createBooking(anyLong(), any(), any(), any(), any(), anyLong(), org.mockito.ArgumentMatchers.anyInt(), any(), any()))
                .thenReturn(booking());
        when(gateway.createPayment(anyLong(), any(), any(), anyLong(), any(), any())).thenReturn(payment());
    }

    private static HttpClientErrorException square(String code) {
        return new HttpClientErrorException(HttpStatus.PAYMENT_REQUIRED, "Payment Required", new HttpHeaders(),
                ("{\"errors\":[{\"code\":\"" + code + "\"}]}").getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    @Test
    void offUnlessPaymentsFlagIsSet() throws Exception {
        disabled.perform(json(DEP, deposit(REAL_PHONE, ""))).andExpect(status().isNotFound());
        disabled.perform(json(SVC, service(VAR, REAL_PHONE, ""))).andExpect(status().isNotFound());
        verify(gateway, never()).createPayment(anyLong(), any(), any(), anyLong(), any(), any());
    }

    @Test
    void depositBooksChargesOneHundredAndRunsEverySideEffect() throws Exception {
        stubBookingAndPayment();
        mvc.perform(json(DEP, deposit(REAL_PHONE, ""))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.booking_id").value("BK-1")).andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.duration_minutes").value(120)).andExpect(jsonPath("$.full_price").value(450.0))
                .andExpect(jsonPath("$.deposit_amount").value(100.0)).andExpect(jsonPath("$.remaining_balance").value(350.0))
                .andExpect(jsonPath("$.artist_name").value("Anna K.")).andExpect(jsonPath("$.payment_id").value("PAY-1"))
                .andExpect(jsonPath("$.offer_discount").doesNotExist())
                .andExpect(jsonPath("$.square_customer_id").doesNotExist());

        verify(gateway).createPayment(eq(2L), any(), eq("cnon:card-nonce-ok"), eq(10_000L), eq("CUST-7"),
                eq("Deposit for " + TECH.name() + " (booking BK-1)"));
        verify(squareClient).setCustomerAttribute("CUST-7", "marketing_traffic_source", "google / cpc / lips");
        verify(squareClient).setCustomerAttribute("CUST-7", "sms_marketing_consent", "Opted In");
        verify(squareClient).setCustomerAttribute("CUST-7", "email_marketing_consent", "Opted In");
        verify(telegram, never()).sendPaymentFailedAlert(any(), any());
        assertThat(jdbc.queryForMap("SELECT * FROM marketing.submissions WHERE square_booking_id = 'BK-1'"))
                .containsEntry("submission_type", "booking").containsEntry("service_name", TECH.name())
                .containsEntry("customer_phone", "+16193238888");
        assertThat(jdbc.queryForObject("SELECT price FROM marketing.submissions WHERE square_booking_id = 'BK-1'",
                java.math.BigDecimal.class).doubleValue()).isEqualTo(450.0);
        assertThat(count("attribution", "booking_id = 'BK-1' AND source = 'google'")).isEqualTo(1);
        assertThat(count("sms_consent", "phone_number = '+16193238888' AND consented")).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT * FROM marketing.contacts WHERE phone_number = '+16193238888'"))
                .containsEntry("square_customer_id", "CUST-7").containsEntry("booking_status", "ACCEPTED");
    }

    @Test
    void sameIdempotencyKeyIsUsedForTheTransportRetryAndNoSecondBookingIsMade() throws Exception {
        when(gateway.createBooking(anyLong(), any(), any(), any(), any(), anyLong(), org.mockito.ArgumentMatchers.anyInt(), any(), any()))
                .thenReturn(booking());
        when(gateway.createPayment(anyLong(), any(), any(), anyLong(), any(), any()))
                .thenThrow(new org.springframework.web.client.ResourceAccessException("read timed out"))
                .thenReturn(payment());
        mvc.perform(json(DEP, deposit(REAL_PHONE, ""))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.payment_id").value("PAY-1"));
        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(gateway, times(2)).createPayment(anyLong(), keys.capture(), any(), anyLong(), any(), any());
        assertThat(keys.getAllValues().get(0)).isEqualTo(keys.getAllValues().get(1));
        verify(gateway, times(1)).createBooking(anyLong(), any(), any(), any(), any(), anyLong(),
                org.mockito.ArgumentMatchers.anyInt(), any(), any());
        verify(gateway, never()).cancelBooking(anyLong(), any());
    }

    @Test
    void declinedCardAnswers402CancelsTheBookingAlertsStaffAndWritesNothing() throws Exception {
        when(gateway.createBooking(anyLong(), any(), any(), any(), any(), anyLong(), org.mockito.ArgumentMatchers.anyInt(), any(), any()))
                .thenReturn(booking());
        when(gateway.createPayment(anyLong(), any(), any(), anyLong(), any(), any())).thenThrow(square("CVV_FAILURE"));
        mvc.perform(json(DEP, deposit(REAL_PHONE, ""))).andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.detail").value("Your card was declined. Please try a different card."));
        verify(gateway).cancelBooking(2L, "BK-1");
        ArgumentCaptor<PaymentFailedNotification> alert = ArgumentCaptor.forClass(PaymentFailedNotification.class);
        verify(telegram).sendPaymentFailedAlert(eq(2L), alert.capture());
        assertThat(alert.getValue().clientError()).isTrue();
        assertThat(alert.getValue().errorCode()).isEqualTo("CVV_FAILURE");
        assertThat(alert.getValue().amount()).isEqualTo(100.0);
        assertThat(alert.getValue().customerName()).isEqualTo("Ann Test");
        assertThat(count("submissions", null)).isZero();
        assertThat(count("contacts", null)).isZero();
    }

    @Test
    void nonDeclinePaymentErrorsAnswer502WithAnOurSideAlert() throws Exception {
        when(gateway.createBooking(anyLong(), any(), any(), any(), any(), anyLong(), org.mockito.ArgumentMatchers.anyInt(), any(), any()))
                .thenReturn(booking());
        when(gateway.createPayment(anyLong(), any(), any(), anyLong(), any(), any())).thenThrow(square("GENERIC_DECLINE"));
        mvc.perform(json(DEP, deposit(REAL_PHONE, ""))).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail").value("Unable to process payment"));
        verify(gateway).cancelBooking(2L, "BK-1");
        ArgumentCaptor<PaymentFailedNotification> alert = ArgumentCaptor.forClass(PaymentFailedNotification.class);
        verify(telegram).sendPaymentFailedAlert(eq(2L), alert.capture());
        assertThat(alert.getValue().clientError()).isFalse();
    }

    @Test
    void cancelFailureAndAlertFailureNeverChangeTheCustomerAnswer() throws Exception {
        when(gateway.createBooking(anyLong(), any(), any(), any(), any(), anyLong(), org.mockito.ArgumentMatchers.anyInt(), any(), any()))
                .thenReturn(booking());
        when(gateway.createPayment(anyLong(), any(), any(), anyLong(), any(), any())).thenThrow(square("CARD_DECLINED"));
        org.mockito.Mockito.doThrow(new RuntimeException("square down")).when(gateway).cancelBooking(anyLong(), any());
        when(telegram.sendPaymentFailedAlert(any(), any())).thenThrow(new RuntimeException("tg down"));
        mvc.perform(json(DEP, deposit(REAL_PHONE, ""))).andExpect(status().isPaymentRequired());
    }

    @Test
    void slotConflictAnswers409AndNeverCharges() throws Exception {
        when(gateway.createBooking(anyLong(), any(), any(), any(), any(), anyLong(), org.mockito.ArgumentMatchers.anyInt(), any(), any()))
                .thenThrow(square("BOOKING_TIME_NOT_AVAILABLE"));
        mvc.perform(json(DEP, deposit(REAL_PHONE, ""))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("This time slot is no longer available."));
        verify(gateway, never()).createPayment(anyLong(), any(), any(), anyLong(), any(), any());
        assertThat(count("submissions", null)).isZero();
    }

    @Test
    void otherBookingErrorsAnswer502AndNeverCharge() throws Exception {
        when(gateway.createBooking(anyLong(), any(), any(), any(), any(), anyLong(), org.mockito.ArgumentMatchers.anyInt(), any(), any()))
                .thenThrow(new RuntimeException("timeout"));
        mvc.perform(json(DEP, deposit(REAL_PHONE, ""))).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail").value("Unable to create appointment in Square"));
        verify(gateway, never()).createPayment(anyLong(), any(), any(), anyLong(), any(), any());
    }

    @Test
    void unknownTechniqueOrProviderAnswer400() throws Exception {
        mvc.perform(json(DEP, deposit(REAL_PHONE, "").replace(TECH.slug(), "nope"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Unknown technique 'nope'"));
        mvc.perform(json(DEP, deposit(REAL_PHONE, "").replace(ANNA, "TM-X"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("'TM-X' doesn't offer this technique"));
        verify(squareClient, never()).createLandingCustomer(any(), any(), any(), any(), any());
    }

    @Test
    void depositNeedsACardNonce() throws Exception {
        mvc.perform(json(DEP, deposit(REAL_PHONE, "").replace(",\"source_id\":\"cnon:card-nonce-ok\"", "")))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void promoEnrollsOnDepositAndItsFailureNeverFailsTheBooking() throws Exception {
        stubBookingAndPayment();
        when(notifications.enrollRebookingPromo(any())).thenThrow(new RuntimeException("boom"));
        mvc.perform(json(DEP, deposit(REAL_PHONE, ",\"promo\":{\"code\":\"VIP10\",\"exp_epoch_seconds\":2000000000,"
                + "\"signature\":\"sig\"}"))).andExpect(status().isCreated());
        ArgumentCaptor<InternalNotificationController.RebookingPromoEnrollRequest> req =
                ArgumentCaptor.forClass(InternalNotificationController.RebookingPromoEnrollRequest.class);
        verify(notifications).enrollRebookingPromo(req.capture());
        assertThat(req.getValue().squareCustomerId()).isEqualTo("CUST-7");
        assertThat(req.getValue().promoCode()).isEqualTo("VIP10");
        assertThat(req.getValue().businessId()).isEqualTo(2L);
        assertThat(req.getValue().customerName()).isEqualTo("Ann Test");
        assertThat(count("submissions", "square_booking_id = 'BK-1'")).isEqualTo(1);
    }

    @Test
    void serviceWithDepositChargesAndLeavesAPolicyNoteOnTheBooking() throws Exception {
        stubBookingAndPayment();
        mvc.perform(json(SVC, service(VAR, REAL_PHONE, ",\"source_id\":\"cnon:card-nonce-ok\",\"deposit_policy_accepted\":true")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.service_name").value("Lip Blush"))
                .andExpect(jsonPath("$.deposit_amount").value(100.0)).andExpect(jsonPath("$.remaining_balance").value(350.0))
                .andExpect(jsonPath("$.payment_id").value("PAY-1")).andExpect(jsonPath("$.offer_discount").value(0.0));
        ArgumentCaptor<String> seller = ArgumentCaptor.forClass(String.class);
        verify(gateway).createBooking(eq(2L), eq("CUST-7"), eq("2026-12-05T18:30:00Z"), eq(ANNA), eq(VAR), eq(9L), eq(120),
                any(), seller.capture());
        assertThat(seller.getValue()).startsWith("Deposit policy accepted online ").contains("the $100 deposit goes toward")
                .endsWith("for a same-day cancellation or reschedule, or a no-show.");
        verify(gateway).createPayment(eq(2L), any(), eq("cnon:card-nonce-ok"), eq(10_000L), eq("CUST-7"),
                eq("Deposit for Lip Blush (booking BK-1); cancellation policy accepted online"));
    }

    @Test
    void serviceWithoutDepositIsAPlainBookingWithNoChargeAndNoPromo() throws Exception {
        when(gateway.createBooking(anyLong(), any(), any(), any(), any(), anyLong(), org.mockito.ArgumentMatchers.anyInt(), any(), any()))
                .thenReturn(booking());
        mvc.perform(json(SVC, service("VAR-FREE", REAL_PHONE, ",\"promo\":{\"code\":\"VIP10\",\"exp_epoch_seconds\":1,\"signature\":\"s\"}")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.deposit_amount").value(0.0))
                .andExpect(jsonPath("$.remaining_balance").value(60.0)).andExpect(jsonPath("$.payment_id").doesNotExist());
        verify(gateway, never()).createPayment(anyLong(), any(), any(), anyLong(), any(), any());
        verify(notifications, never()).enrollRebookingPromo(any());
        verify(gateway).createBooking(anyLong(), any(), any(), any(), any(), anyLong(), org.mockito.ArgumentMatchers.anyInt(), any(),
                org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    void serviceRuleErrorsAnswer400() throws Exception {
        mvc.perform(json(SVC, service(VAR, REAL_PHONE, ",\"deposit_policy_accepted\":true"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("A card is required for this service's deposit"));
        mvc.perform(json(SVC, service(VAR, REAL_PHONE, ",\"source_id\":\"cnon:card-nonce-ok\""))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Please agree to the deposit and cancellation policy to book"));
        mvc.perform(json(SVC, service("NOPE", REAL_PHONE, ""))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Unknown service option 'NOPE'"));
        mvc.perform(json(SVC, service(VAR, REAL_PHONE, "").replace(ANNA, "TM-X"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("'TM-X' doesn't offer this service"));
        verify(squareClient, never()).createLandingCustomer(any(), any(), any(), any(), any());
    }

    @Test
    void serviceTestPhoneGetsFakeConfirmationAndTouchesNothing() throws Exception {
        mvc.perform(json(SVC, service(VAR, "(619) 555-0142", ",\"deposit_policy_accepted\":true")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("TEST"))
                .andExpect(jsonPath("$.booking_id").value(org.hamcrest.Matchers.startsWith("TEST-")))
                .andExpect(jsonPath("$.payment_id").doesNotExist()).andExpect(jsonPath("$.artist_name").value("Anna K."));
        verify(gateway, never()).createBooking(anyLong(), any(), any(), any(), any(), anyLong(), org.mockito.ArgumentMatchers.anyInt(), any(), any());
        verify(gateway, never()).createPayment(anyLong(), any(), any(), anyLong(), any(), any());
        verify(squareClient, never()).createLandingCustomer(any(), any(), any(), any(), any());
        assertThat(count("submissions", null)).isZero();
    }

    @Test
    void offerDiscountAppliesAboveMinimumAndAClaimFailureOnlyDropsTheDiscount() throws Exception {
        stubBookingAndPayment();
        when(offers.check(2L, "TOK")).thenReturn(new ConsultationOfferService.Offer(true, 7500, 30000, null, "x", "Ann"));
        when(offers.claim(eq(2L), eq("TOK"), eq("CUST-7"), any())).thenReturn(true).thenReturn(false);
        String extra = ",\"source_id\":\"cnon:card-nonce-ok\",\"deposit_policy_accepted\":true,\"offer_token\":\"TOK\"";
        mvc.perform(json(SVC, service(VAR, REAL_PHONE, extra))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.offer_discount").value(75.0));
        mvc.perform(json(SVC, service(VAR, REAL_PHONE, extra))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.offer_discount").value(0.0));
    }

    @Test
    void offerBelowItsMinimumSpendIsNotClaimed() throws Exception {
        when(gateway.createBooking(anyLong(), any(), any(), any(), any(), anyLong(), org.mockito.ArgumentMatchers.anyInt(), any(), any()))
                .thenReturn(booking());
        when(offers.check(2L, "TOK")).thenReturn(new ConsultationOfferService.Offer(true, 7500, 30000, null, "x", "Ann"));
        mvc.perform(json(SVC, service("VAR-FREE", REAL_PHONE, ",\"offer_token\":\"TOK\"")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.offer_discount").value(0.0));
        verify(offers, never()).claim(anyLong(), any(), any(), any());
    }

    @Test
    void honeypotIsRejectedAndLoggedUnderEachEndpointName() throws Exception {
        mvc.perform(json(DEP, deposit(REAL_PHONE, ",\"website\":\"http://spam\""))).andExpect(status().isBadRequest());
        mvc.perform(json(SVC, service(VAR, REAL_PHONE, ",\"website\":\"http://spam\""))).andExpect(status().isBadRequest());
        assertThat(count("abuse_blocks", "reason = 'honeypot' AND endpoint = 'pmu_deposit'")).isEqualTo(1);
        assertThat(count("abuse_blocks", "reason = 'honeypot' AND endpoint = 'pmu_service'")).isEqualTo(1);
        verify(gateway, never()).createPayment(anyLong(), any(), any(), anyLong(), any(), any());
    }
}
