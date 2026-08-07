package com.salonreview.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salonreview.config.InternalApiProperties;
import com.salonreview.config.RebookingProperties;
import com.salonreview.repo.SameDayRebookingGroupMembershipRepository;
import com.salonreview.sms.RebookingPromoSigner;
import com.salonreview.sms.TwilioSmsService;
import com.salonreview.square.SquareClient;
import com.salonreview.telegram.TelegramNotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone {@code MockMvc} — unlike every other controller in this app, auth here is the
 * controller's own {@code X-Internal-Api-Key} check (there's no session for service-to-service
 * callers), so it's tested directly rather than left to {@code SecurityConfig}.
 */
class InternalNotificationControllerTest {

    private static final String BODY = "{\"source\":\"mani\",\"customerName\":\"Jane\",\"phoneNumber\":\"+15551234567\"," +
            "\"requestedServices\":\"manicure\",\"preferredStartAt\":\"2026-08-01T18:00:00Z\",\"note\":null}";

    private InternalApiProperties props;
    private TelegramNotificationService telegram;
    private TwilioSmsService sms;
    private RebookingPromoSigner promoSigner;
    private RebookingProperties rebookingProperties;
    private SameDayRebookingGroupMembershipRepository groupMembershipRepository;
    private SquareClient square;
    private MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        props = mock(InternalApiProperties.class);
        telegram = mock(TelegramNotificationService.class);
        sms = mock(TwilioSmsService.class);
        promoSigner = mock(RebookingPromoSigner.class);
        rebookingProperties = new RebookingProperties();
        rebookingProperties.setAutoDiscountGroupId("grp1");
        rebookingProperties.setWinbackAutoDiscountGroupId("grp2");
        groupMembershipRepository = mock(SameDayRebookingGroupMembershipRepository.class);
        square = mock(SquareClient.class);
        InternalNotificationController controller = new InternalNotificationController(
                props, telegram, sms, promoSigner, rebookingProperties, groupMembershipRepository, square);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("missing key header → 401")
    void missingKeyReturns401() throws Exception {
        when(props.getKey()).thenReturn("secret");

        mvc.perform(post("/api/internal/notifications/four-hand-request")
                        .contentType("application/json").content(BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("wrong key header → 401")
    void wrongKeyReturns401() throws Exception {
        when(props.getKey()).thenReturn("secret");

        mvc.perform(post("/api/internal/notifications/four-hand-request")
                        .header("X-Internal-Api-Key", "wrong")
                        .contentType("application/json").content(BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("blank configured key → every call 401s, even with a matching-looking header")
    void blankConfiguredKeyAlwaysRejects() throws Exception {
        when(props.getKey()).thenReturn("");

        mvc.perform(post("/api/internal/notifications/four-hand-request")
                        .header("X-Internal-Api-Key", "")
                        .contentType("application/json").content(BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("correct key + alert sent → 200 sent:true")
    void correctKeySentTrue() throws Exception {
        when(props.getKey()).thenReturn("secret");
        when(telegram.sendFourHandRequestAlert(any())).thenReturn(true);

        mvc.perform(post("/api/internal/notifications/four-hand-request")
                        .header("X-Internal-Api-Key", "secret")
                        .contentType("application/json").content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sent").value(true));
    }

    @Test
    @DisplayName("correct key + alert not configured → 200 sent:false, not an error")
    void correctKeySentFalse() throws Exception {
        when(props.getKey()).thenReturn("secret");
        when(telegram.sendFourHandRequestAlert(any())).thenReturn(false);

        mvc.perform(post("/api/internal/notifications/four-hand-request")
                        .header("X-Internal-Api-Key", "secret")
                        .contentType("application/json").content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sent").value(false));
    }

    private static final String SMS_BODY = "{\"templateKey\":\"four_hand_request_received\","
            + "\"phoneNumber\":\"+15551234567\",\"variables\":{\"name\":\"Jane\"}}";

    @Test
    @DisplayName("sms/send: missing key → 401")
    void smsSendMissingKeyReturns401() throws Exception {
        when(props.getKey()).thenReturn("secret");

        mvc.perform(post("/api/internal/notifications/sms/send")
                        .contentType("application/json").content(SMS_BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("sms/send: correct key + sent → 200 sent:true, reason:null")
    void smsSendSentTrue() throws Exception {
        when(props.getKey()).thenReturn("secret");
        when(sms.sendTemplated(anyString(), anyString(), any())).thenReturn(new TwilioSmsService.SmsSendResult(true, null));

        mvc.perform(post("/api/internal/notifications/sms/send")
                        .header("X-Internal-Api-Key", "secret")
                        .contentType("application/json").content(SMS_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sent").value(true))
                .andExpect(jsonPath("$.reason").doesNotExist());
    }

    @Test
    @DisplayName("sms/send: correct key + blocked → 200 sent:false with reason, not an error")
    void smsSendBlockedWithReason() throws Exception {
        when(props.getKey()).thenReturn("secret");
        when(sms.sendTemplated(anyString(), anyString(), any())).thenReturn(new TwilioSmsService.SmsSendResult(false, "no_consent"));

        mvc.perform(post("/api/internal/notifications/sms/send")
                        .header("X-Internal-Api-Key", "secret")
                        .contentType("application/json").content(SMS_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sent").value(false))
                .andExpect(jsonPath("$.reason").value("no_consent"));
    }

    private static final String ENROLL_BODY = "{\"squareCustomerId\":\"cust1\",\"expEpochSeconds\":9999999999,\"signature\":\"sig123\"}";

    @Test
    @DisplayName("rebooking-promo/enroll: missing key → 401")
    void enrollMissingKeyReturns401() throws Exception {
        when(props.getKey()).thenReturn("secret");

        mvc.perform(post("/api/internal/rebooking-promo/enroll")
                        .contentType("application/json").content(ENROLL_BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("rebooking-promo/enroll: invalid signature → 200 enrolled:false, no Square call")
    void enrollInvalidSignatureNotEnrolled() throws Exception {
        when(props.getKey()).thenReturn("secret");
        when(promoSigner.verify("REBOOK10", 9999999999L, "sig123")).thenReturn(false);

        mvc.perform(post("/api/internal/rebooking-promo/enroll")
                        .header("X-Internal-Api-Key", "secret")
                        .contentType("application/json").content(ENROLL_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enrolled").value(false))
                .andExpect(jsonPath("$.reason").value("invalid_signature"));

        verifyNoSquareCall();
    }

    @Test
    @DisplayName("rebooking-promo/enroll: valid signature + unexpired → enrolls in Square group, writes membership")
    void enrollValidSignatureEnrolls() throws Exception {
        when(props.getKey()).thenReturn("secret");
        when(promoSigner.verify("REBOOK10", 9999999999L, "sig123")).thenReturn(true);

        mvc.perform(post("/api/internal/rebooking-promo/enroll")
                        .header("X-Internal-Api-Key", "secret")
                        .contentType("application/json").content(ENROLL_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enrolled").value(true));

        verify(square).addCustomerToGroup("cust1", "grp1");
        verify(groupMembershipRepository).save(any());
        verify(telegram).sendRebookingPromoAlert(any(), any(), any());
    }

    @Test
    @DisplayName("rebooking-promo/enroll: valid signature but already-expired exp → not enrolled")
    void enrollExpiredNotEnrolled() throws Exception {
        when(props.getKey()).thenReturn("secret");
        String pastBody = "{\"squareCustomerId\":\"cust1\",\"expEpochSeconds\":1,\"signature\":\"sig123\"}";
        when(promoSigner.verify("REBOOK10", 1L, "sig123")).thenReturn(true);

        mvc.perform(post("/api/internal/rebooking-promo/enroll")
                        .header("X-Internal-Api-Key", "secret")
                        .contentType("application/json").content(pastBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enrolled").value(false))
                .andExpect(jsonPath("$.reason").value("expired"));

        verifyNoSquareCall();
    }

    private void verifyNoSquareCall() {
        org.mockito.Mockito.verifyNoInteractions(square);
    }

    @Test
    @DisplayName("rebooking-promo/enroll: promoCode WINBACK5 → verifies against WINBACK5, enrolls in the winback group")
    void enrollWinbackPromoCodeUsesWinbackGroup() throws Exception {
        when(props.getKey()).thenReturn("secret");
        String winbackBody = "{\"squareCustomerId\":\"cust1\",\"expEpochSeconds\":9999999999,"
                + "\"signature\":\"sig123\",\"promoCode\":\"WINBACK5\"}";
        when(promoSigner.verify("WINBACK5", 9999999999L, "sig123")).thenReturn(true);

        mvc.perform(post("/api/internal/rebooking-promo/enroll")
                        .header("X-Internal-Api-Key", "secret")
                        .contentType("application/json").content(winbackBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enrolled").value(true));

        verify(square).addCustomerToGroup("cust1", "grp2");
        verify(promoSigner, org.mockito.Mockito.never())
                .verify(org.mockito.ArgumentMatchers.eq("REBOOK10"), org.mockito.ArgumentMatchers.anyLong(), anyString());
    }

    @Test
    @DisplayName("rebooking-promo/enroll: promoCode omitted → defaults to REBOOK10 (backward compatible)")
    void enrollMissingPromoCodeDefaultsToRebook10() throws Exception {
        when(props.getKey()).thenReturn("secret");
        when(promoSigner.verify("REBOOK10", 9999999999L, "sig123")).thenReturn(true);

        mvc.perform(post("/api/internal/rebooking-promo/enroll")
                        .header("X-Internal-Api-Key", "secret")
                        .contentType("application/json").content(ENROLL_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enrolled").value(true));

        verify(square).addCustomerToGroup("cust1", "grp1");
    }
}
