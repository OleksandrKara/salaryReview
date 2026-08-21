package com.salonreview.sms;

import com.salonreview.domain.SmsTemplateOverride;
import com.salonreview.repo.SmsMessageRepository;
import com.salonreview.repo.SmsTemplateOverrideRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SmsMessageTemplateServiceTest {

    private static final Long BUSINESS_ID = 1L;
    private static final String KEY = "lead_follow_up_nudge"; // single-variant key
    private static final String MULTI_VARIANT_KEY = "checkout_review_negative";
    private static final String PHONE = "+15551234567";

    private SmsTemplateOverrideRepository overrides;
    private SmsMessageRepository messages;
    private SmsMessageTemplateService service;

    @BeforeEach
    void setUp() {
        overrides = mock(SmsTemplateOverrideRepository.class);
        messages = mock(SmsMessageRepository.class);
        service = new SmsMessageTemplateService(overrides, messages);
    }

    @Test
    @DisplayName("no override on file → renders the in-code default")
    void rendersDefaultWhenNoOverride() {
        when(overrides.findByBusinessIdAndTemplateKey(BUSINESS_ID, KEY)).thenReturn(Optional.empty());

        String body = service.render(BUSINESS_ID, KEY, PHONE,
                Map.of("greeting", "Hi Jane!", "sender", "Lucy", "businessName", "AK.LUX.NAILS"));

        assertThat(body).isEqualTo("Hi Jane! It's Lucy from AK.LUX.NAILS 💛 Do you need help with more openings "
                + "or is there anything specific you are looking for?");
    }

    @Test
    @DisplayName("an override on file wins over the default")
    void rendersOverrideWhenPresent() {
        when(overrides.findByBusinessIdAndTemplateKey(BUSINESS_ID, KEY)).thenReturn(
                Optional.of(SmsTemplateOverride.builder().businessId(BUSINESS_ID).templateKey(KEY)
                        .body("{{greeting}} custom text from {{sender}}").build()));

        String body = service.render(BUSINESS_ID, KEY, PHONE, Map.of("greeting", "Hi Jane!", "sender", "Lucy"));

        assertThat(body).isEqualTo("Hi Jane! custom text from Lucy");
    }

    @Test
    @DisplayName("unknown template key throws — a programmer error, not a data condition")
    void unknownKeyThrows() {
        assertThatThrownBy(() -> service.render(BUSINESS_ID, "does_not_exist", PHONE, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a multi-variant key with no override cycles through every variant by prior send count, not the same one every time")
    void multiVariantKeyRotatesByPriorSendCount() {
        when(overrides.findByBusinessIdAndTemplateKey(BUSINESS_ID, MULTI_VARIANT_KEY)).thenReturn(Optional.empty());
        int variantCount = SmsMessageTemplateCatalog.get(MULTI_VARIANT_KEY).defaultBodies().size();
        assertThat(variantCount).isGreaterThan(1);

        List<String> renderedBodies = new java.util.ArrayList<>();
        for (long sentBefore = 0; sentBefore < variantCount; sentBefore++) {
            when(messages.countByBusinessIdAndPhoneNumberAndTemplateKeyAndDirectionAndStatus(
                    BUSINESS_ID, PHONE, MULTI_VARIANT_KEY, "OUTBOUND", "SENT")).thenReturn(sentBefore);
            renderedBodies.add(service.render(BUSINESS_ID, MULTI_VARIANT_KEY, PHONE, Map.of("sender", "Lucy")));
        }

        assertThat(renderedBodies).doesNotHaveDuplicates();
        // wraps back to the first variant once every variant has been used once
        when(messages.countByBusinessIdAndPhoneNumberAndTemplateKeyAndDirectionAndStatus(
                BUSINESS_ID, PHONE, MULTI_VARIANT_KEY, "OUTBOUND", "SENT")).thenReturn((long) variantCount);
        String wrapped = service.render(BUSINESS_ID, MULTI_VARIANT_KEY, PHONE, Map.of("sender", "Lucy"));
        assertThat(wrapped).isEqualTo(renderedBodies.get(0));
    }

    @Test
    @DisplayName("an override on a multi-variant key wins for every send — no rotation once customized")
    void overrideDisablesRotation() {
        when(overrides.findByBusinessIdAndTemplateKey(BUSINESS_ID, MULTI_VARIANT_KEY)).thenReturn(
                Optional.of(SmsTemplateOverride.builder().businessId(BUSINESS_ID).templateKey(MULTI_VARIANT_KEY)
                        .body("custom -{{sender}}").build()));

        String body = service.render(BUSINESS_ID, MULTI_VARIANT_KEY, PHONE, Map.of("sender", "Lucy"));

        assertThat(body).isEqualTo("custom -Lucy");
        verifyNoInteractions(messages);
    }

    @Test
    @DisplayName("save() persists a trimmed override and returns it as customized")
    void saveStoresOverride() {
        when(overrides.findByBusinessIdAndTemplateKey(BUSINESS_ID, KEY)).thenReturn(Optional.empty());
        when(overrides.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var view = service.save(BUSINESS_ID, KEY, "  custom body  ", "owner");

        assertThat(view.body()).isEqualTo("custom body");
        assertThat(view.customized()).isTrue();
        var captor = org.mockito.ArgumentCaptor.forClass(SmsTemplateOverride.class);
        verify(overrides).save(captor.capture());
        assertThat(captor.getValue().getBody()).isEqualTo("custom body");
        assertThat(captor.getValue().getUpdatedBy()).isEqualTo("owner");
    }

    @Test
    @DisplayName("save() rejects a blank body")
    void saveRejectsBlankBody() {
        assertThatThrownBy(() -> service.save(BUSINESS_ID, KEY, "   ", "owner"))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(overrides);
    }

    @Test
    @DisplayName("save() rejects an unknown template key")
    void saveRejectsUnknownKey() {
        assertThatThrownBy(() -> service.save(BUSINESS_ID, "does_not_exist", "text", "owner"))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(overrides);
    }

    @Test
    @DisplayName("resetToDefault() deletes the override and returns the default body, uncustomized")
    void resetDeletesOverride() {
        var view = service.resetToDefault(BUSINESS_ID, KEY);

        verify(overrides).deleteByBusinessIdAndTemplateKey(BUSINESS_ID, KEY);
        assertThat(view.customized()).isFalse();
        assertThat(view.body()).contains("Do you need help with more openings");
    }

    @Test
    @DisplayName("list() reports every catalog entry, marking which ones are customized for this business")
    void listMarksCustomizedEntries() {
        when(overrides.findAllByBusinessId(BUSINESS_ID)).thenReturn(List.of(
                SmsTemplateOverride.builder().businessId(BUSINESS_ID).templateKey(KEY).body("custom").build()));

        List<SmsMessageTemplateService.TemplateView> views = service.list(BUSINESS_ID);

        assertThat(views).hasSize(SmsMessageTemplateCatalog.all().size());
        var leadFollowUp = views.stream().filter(v -> v.key().equals(KEY)).findFirst().orElseThrow();
        assertThat(leadFollowUp.customized()).isTrue();
        assertThat(leadFollowUp.body()).isEqualTo("custom");
        var untouched = views.stream().filter(v -> !v.key().equals(KEY)).findFirst().orElseThrow();
        assertThat(untouched.customized()).isFalse();
    }
}
