package com.salonreview.intake;

import com.salonreview.config.SquareCredentialCipher;
import com.salonreview.domain.IntakeForm;
import com.salonreview.domain.Provider;
import com.salonreview.repo.IntakeFormRepository;
import com.salonreview.repo.ProviderRepository;
import com.salonreview.repo.SquareCustomerMirrorRepository;
import com.salonreview.sms.TwilioSmsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IntakeFormServiceTest {

    IntakeFormRepository forms = mock(IntakeFormRepository.class);
    ProviderRepository providers = mock(ProviderRepository.class);
    TwilioSmsService sms = mock(TwilioSmsService.class);
    IntakeFormService service;

    @BeforeEach
    void setUp() {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        service = new IntakeFormService(forms, mock(SquareCustomerMirrorRepository.class), providers, sms,
                new SquareCredentialCipher(key), "https://salon.akluxnails.com/");
    }

    IntakeForm form() {
        return IntakeForm.builder().id(7L).businessId(1L).squareCustomerId("C1").squareBookingId("B1")
                .phoneNumber("+15551234567").firstName("Jane").token("tok123").status(IntakeForm.PENDING)
                .appointmentStartAt(Instant.parse("2026-11-19T22:30:00Z")).build();
    }

    @Test
    void answersAreEncryptedAtRestAndRoundTrip() {
        IntakeForm f = form();
        when(forms.findByToken("tok123")).thenReturn(Optional.of(f));
        Map<String, String> a = IntakeAnswersTest.valid();
        a.put("allergies", "YES");
        a.put("allergiesDetails", "latex gloves");
        assertThat(service.submit("tok123", a, "Jane Doe", true, true)).isEqualTo(IntakeFormService.SubmitResult.OK);
        assertThat(f.getAnswersEncrypted()).isNotBlank().doesNotContain("latex").doesNotContain("allergies");
        assertThat(f.isSigned()).isTrue();
        assertThat(f.getSignedVia()).isEqualTo(IntakeForm.VIA_CLIENT_FORM);
        assertThat(f.isNeedsAttention()).isTrue();
        assertThat(service.decryptAnswers(f)).containsEntry("allergiesDetails", "latex gloves");
        assertThat(f.getConsentText()).contains("the team of this salon").contains("technician, managers and owner");
        assertThat(f.getConsentVersion()).isEqualTo(IntakeFormService.CONSENT_VERSION);
    }

    @Test
    void submitWithoutConsentTickStoresNothing() {
        IntakeForm f = form();
        when(forms.findByToken("tok123")).thenReturn(Optional.of(f));
        assertThatThrownBy(() -> service.submit("tok123", IntakeAnswersTest.valid(), "Jane Doe", true, false))
                .hasMessage("consent");
        assertThat(f.isSigned()).isFalse();
        assertThat(f.getAnswersEncrypted()).isNull();
    }

    @Test
    void consentNamesTheLegalEntity() {
        assertThat(IntakeFormService.consentText("Anna Kara's Brow Studio LLC dba AK.LUX.NAILS"))
                .contains("the team of Anna Kara's Brow Studio LLC dba AK.LUX.NAILS (my technician, managers and owner)");
    }

    @Test
    void secondSubmitDoesNotOverwrite() {
        IntakeForm f = form();
        when(forms.findByToken("tok123")).thenReturn(Optional.of(f));
        service.submit("tok123", IntakeAnswersTest.valid(), "Jane Doe", true, true);
        String stored = f.getAnswersEncrypted();
        Map<String, String> other = IntakeAnswersTest.valid();
        other.put("service", "PEDICURE");
        assertThat(service.submit("tok123", other, "Someone Else", true, true)).isEqualTo(IntakeFormService.SubmitResult.ALREADY_SIGNED);
        assertThat(f.getAnswersEncrypted()).isEqualTo(stored);
        assertThat(f.getSignatureName()).isEqualTo("Jane Doe");
    }

    @Test
    void unknownTokenAndBadInputStoreNothing() {
        when(forms.findByToken("nope")).thenReturn(Optional.empty());
        assertThat(service.submit("nope", IntakeAnswersTest.valid(), "Jane Doe", true, true)).isEqualTo(IntakeFormService.SubmitResult.NOT_FOUND);
        IntakeForm f = form();
        when(forms.findByToken("tok123")).thenReturn(Optional.of(f));
        assertThatThrownBy(() -> service.submit("tok123", IntakeAnswersTest.valid(), "Jane Doe", false, true)).hasMessage("signature");
        assertThatThrownBy(() -> service.submit("tok123", IntakeAnswersTest.valid(), " ", true, true)).hasMessage("signature");
        Map<String, String> bad = new HashMap<>(IntakeAnswersTest.valid());
        bad.put("skin", "??");
        assertThatThrownBy(() -> service.submit("tok123", bad, "Jane Doe", true, true)).isInstanceOf(IntakeAnswers.Invalid.class);
        assertThat(f.isSigned()).isFalse();
        assertThat(f.getAnswersEncrypted()).isNull();
        verify(forms, never()).save(any());
    }

    @Test
    void initialTextWithTechnicianUsesHerNameAndLink() {
        IntakeForm f = form();
        f.setProviderId(5L);
        Provider p = new Provider();
        p.setName("Anastasia Ivanova");
        when(providers.findByIdAndBusinessId(5L, 1L)).thenReturn(Optional.of(p));
        when(sms.sendTemplated(anyLong(), anyString(), anyString(), anyMap())).thenReturn(new TwilioSmsService.SmsSendResult(true, null));
        assertThat(service.sendText(f, false)).isEqualTo("SENT");
        ArgumentCaptor<Map<String, String>> vars = ArgumentCaptor.forClass(Map.class);
        verify(sms).sendTemplated(eq(1L), eq(IntakeFormService.TEMPLATE_INITIAL_TECH), eq("+15551234567"), vars.capture());
        assertThat(vars.getValue()).containsEntry("greeting", "Hi Jane!").containsEntry("technician", "Anastasia")
                .containsEntry("link", "https://salon.akluxnails.com/intake/tok123");
        assertThat(f.getStatus()).isEqualTo(IntakeForm.SENT);
        assertThat(f.getSentAt()).isNotNull();
        assertThat(f.getInitialSmsState()).isEqualTo("SENT");
    }

    @Test
    void junkNameGetsPlainGreetingAndNoTechnicianTemplate() {
        IntakeForm f = form();
        f.setFirstName(null);
        when(sms.sendTemplated(anyLong(), anyString(), anyString(), anyMap())).thenReturn(new TwilioSmsService.SmsSendResult(true, null));
        service.sendText(f, false);
        ArgumentCaptor<Map<String, String>> vars = ArgumentCaptor.forClass(Map.class);
        verify(sms).sendTemplated(eq(1L), eq(IntakeFormService.TEMPLATE_INITIAL), anyString(), vars.capture());
        assertThat(vars.getValue()).containsEntry("greeting", "Hi!").doesNotContainKey("technician");
    }

    @Test
    void cleanFirstNameRejectsJunk() {
        assertThat(IntakeFormService.cleanFirstName("jane")).isEqualTo("Jane");
        assertThat(IntakeFormService.cleanFirstName("Unknown")).isNull();
        assertThat(IntakeFormService.cleanFirstName("+15551234567")).isNull();
        assertThat(IntakeFormService.cleanFirstName(null)).isNull();
    }

    @Test
    void stopAndFailuresMapToStatesWithoutMarkingSent() {
        IntakeForm f = form();
        when(sms.sendTemplated(anyLong(), anyString(), anyString(), anyMap())).thenReturn(new TwilioSmsService.SmsSendResult(false, "blocked"));
        assertThat(service.sendText(f, false)).isEqualTo("SKIPPED_BLOCKED");
        assertThat(f.getSentAt()).isNull();
        assertThat(f.getStatus()).isEqualTo(IntakeForm.PENDING);
        when(sms.sendTemplated(anyLong(), anyString(), anyString(), anyMap())).thenReturn(new TwilioSmsService.SmsSendResult(false, "send_failed"));
        assertThat(service.sendText(f, true)).isEqualTo("SEND_FAILED");
        assertThat(f.getReminderSmsState()).isEqualTo("SEND_FAILED");
        when(sms.sendTemplated(anyLong(), anyString(), anyString(), anyMap())).thenReturn(new TwilioSmsService.SmsSendResult(false, "automation_disabled"));
        assertThat(service.sendText(f, true)).isEqualTo("SKIPPED_AUTOMATION_DISABLED");
    }

    @Test
    void manualResendLeavesAutomaticStatesAlone() {
        IntakeForm f = form();
        f.setInitialSmsState("SENT");
        f.setStatus(IntakeForm.SENT);
        f.setSentAt(Instant.parse("2026-11-17T20:00:00Z"));
        when(sms.sendTemplated(anyLong(), anyString(), anyString(), anyMap())).thenReturn(new TwilioSmsService.SmsSendResult(true, null));
        service.sendText(f, true, true);
        assertThat(f.getReminderSmsState()).isNull();
        assertThat(f.getReminderSentAt()).isNull();
        assertThat(f.getLastManualResendAt()).isNotNull();
    }

    @Test
    void paperReceiptStoresNoAnswersAndSignedIsNotDowngraded() {
        IntakeForm f = form();
        service.markReceivedOnPaper(f);
        assertThat(f.isSigned()).isTrue();
        assertThat(f.getSignedVia()).isEqualTo(IntakeForm.VIA_STAFF_PAPER);
        assertThat(f.getAnswersEncrypted()).isNull();
        assertThat(service.decryptAnswers(f)).isEmpty();
        Instant at = f.getSignedAt();
        service.markReceivedOnPaper(f);
        assertThat(f.getSignedAt()).isEqualTo(at);
    }

    @Test
    void markStartedStampsOnceAndNeverForSignedForms() {
        IntakeForm f = form();
        when(forms.findByToken("tok123")).thenReturn(Optional.of(f));
        assertThat(service.markStarted("tok123")).isTrue();
        Instant first = f.getStartedAt();
        assertThat(first).isNotNull();
        assertThat(f.getOpenedAt()).isEqualTo(first);
        service.markStarted("tok123");
        assertThat(f.getStartedAt()).isEqualTo(first);
        assertThat(service.markStarted("nope")).isFalse();
        IntakeForm signed = form();
        signed.setStatus(IntakeForm.SIGNED);
        when(forms.findByToken("signed")).thenReturn(Optional.of(signed));
        service.markStarted("signed");
        assertThat(signed.getStartedAt()).isNull();
    }
}
