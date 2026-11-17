package com.salonreview.intake;

import com.salonreview.sms.SmsMessageClass;
import com.salonreview.sms.SmsMessageTemplateCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IntakeTemplatesTest {

    private static final List<String> KEYS = List.of(IntakeFormService.TEMPLATE_INITIAL_TECH, IntakeFormService.TEMPLATE_INITIAL,
            IntakeFormService.TEMPLATE_REMINDER_TECH, IntakeFormService.TEMPLATE_REMINDER);

    @Test
    void templatesAreTransactionalSingleSegmentSizedAndPlain() {
        for (String key : KEYS) {
            SmsMessageTemplateCatalog.TemplateDefault t = SmsMessageTemplateCatalog.get(key);
            assertThat(t).as(key).isNotNull();
            assertThat(t.automationKey()).isEqualTo("first_visit_intake_form");
            assertThat(t.messageClass()).isEqualTo(SmsMessageClass.TRANSACTIONAL);
            String body = t.defaultBody()
                    .replace("{{greeting}}", "Hi Jane!").replace("{{sender}}", "Lucy")
                    .replace("{{businessName}}", "AK.LUX.NAILS").replace("{{technician}}", "Anastasia")
                    .replace("{{when}}", "Wednesday, Nov 18 at 2:30 PM")
                    .replace("{{link}}", "https://salon.akluxnails.com/intake/" + "x".repeat(32));
            assertThat(body).doesNotContain("{{");
            assertThat(body.length()).isLessThan(480);
            assertThat(body).matches("[\\x20-\\x7E]+");
            assertThat(body).doesNotContain("Anna").doesNotContain("--");
            for (String v : t.variables()) {
                assertThat(t.defaultBody()).contains("{{" + v + "}}");
            }
        }
    }

    @Test
    void techniciansAreHerAndNeverCenterAnna() {
        assertThat(SmsMessageTemplateCatalog.get(IntakeFormService.TEMPLATE_INITIAL_TECH).defaultBody()).contains("she can prepare");
        assertThat(SmsMessageTemplateCatalog.get(IntakeFormService.TEMPLATE_REMINDER_TECH).defaultBody()).contains("{{technician}}");
    }
}
