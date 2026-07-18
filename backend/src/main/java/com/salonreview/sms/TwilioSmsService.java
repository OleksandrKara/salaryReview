package com.salonreview.sms;

import com.salonreview.domain.TwilioSmsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Sends a registered SMS template on behalf of mani/akluxnails-home, which never see the Twilio
 * credentials themselves (see {@code InternalNotificationController}). Never throws — a missing
 * template, missing consent, unset credentials, or a Twilio-side failure all resolve to
 * {@code sent: false} with a reason, matching {@code TelegramNotificationService}'s contract.
 */
@Service
public class TwilioSmsService {

    private static final Logger log = LoggerFactory.getLogger(TwilioSmsService.class);

    public record SmsSendResult(boolean sent, String reason) {
        static SmsSendResult ok() {
            return new SmsSendResult(true, null);
        }

        static SmsSendResult skipped(String reason) {
            return new SmsSendResult(false, reason);
        }
    }

    private final SmsTemplateRegistry templateRegistry;
    private final TwilioSmsConfigService configService;
    private final SmsConsentRepository consentRepository;
    private final TwilioSmsClient client;

    public TwilioSmsService(SmsTemplateRegistry templateRegistry, TwilioSmsConfigService configService,
                            SmsConsentRepository consentRepository, TwilioSmsClient client) {
        this.templateRegistry = templateRegistry;
        this.configService = configService;
        this.consentRepository = consentRepository;
        this.client = client;
    }

    public SmsSendResult sendTemplated(String templateKey, String phoneNumber, Map<String, String> variables) {
        SmsTemplate template = templateRegistry.find(templateKey);
        if (template == null) {
            return SmsSendResult.skipped("unknown_template");
        }

        if (template.messageClass() == SmsMessageClass.MARKETING && !consentRepository.hasMarketingConsent(phoneNumber)) {
            log.info("SMS template '{}' skipped — no marketing consent for this contact", templateKey);
            return SmsSendResult.skipped("no_consent");
        }

        TwilioSmsConfig config = configService.get();
        if (!config.isConfigured()) {
            log.info("SMS template '{}' skipped — Twilio credentials not configured", templateKey);
            return SmsSendResult.skipped("not_configured");
        }

        try {
            client.send(config, phoneNumber, template.render().apply(variables == null ? Map.of() : variables));
            return SmsSendResult.ok();
        } catch (Exception e) {
            log.warn("SMS template '{}' send failed (caller unaffected): {}", templateKey, e.getMessage());
            return SmsSendResult.skipped("send_failed");
        }
    }
}
