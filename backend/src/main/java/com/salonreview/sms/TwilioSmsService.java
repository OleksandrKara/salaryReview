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
    private final SmsAutomationService automationService;
    private final SmsMessageLogService messageLogService;
    private final TwilioSmsClient client;

    public TwilioSmsService(SmsTemplateRegistry templateRegistry, TwilioSmsConfigService configService,
                            SmsConsentRepository consentRepository, SmsAutomationService automationService,
                            SmsMessageLogService messageLogService, TwilioSmsClient client) {
        this.templateRegistry = templateRegistry;
        this.configService = configService;
        this.consentRepository = consentRepository;
        this.automationService = automationService;
        this.messageLogService = messageLogService;
        this.client = client;
    }

    public SmsSendResult sendTemplated(String templateKey, String phoneNumber, Map<String, String> variables) {
        SmsTemplate template = templateRegistry.find(templateKey);
        if (template == null) {
            // Nothing to render and no automationKey to attribute this to — logged as a bare
            // attempt so it's still visible in the activity view, matching every other outcome.
            messageLogService.logOutbound(templateKey, null, phoneNumber, "", false, "unknown_template", null);
            return SmsSendResult.skipped("unknown_template");
        }

        String body = template.render().apply(variables == null ? Map.of() : variables);
        String automationKey = template.automationKey();

        if (!automationService.isEnabled(automationKey)) {
            log.info("SMS template '{}' skipped — automation '{}' is disabled", templateKey, automationKey);
            messageLogService.logOutbound(templateKey, automationKey, phoneNumber, body, false, "automation_disabled", null);
            return SmsSendResult.skipped("automation_disabled");
        }

        if (template.messageClass() == SmsMessageClass.MARKETING && !consentRepository.hasMarketingConsent(phoneNumber)) {
            log.info("SMS template '{}' skipped — no marketing consent for this contact", templateKey);
            messageLogService.logOutbound(templateKey, automationKey, phoneNumber, body, false, "no_consent", null);
            return SmsSendResult.skipped("no_consent");
        }

        TwilioSmsConfig config = configService.get();
        if (!config.isConfigured()) {
            log.info("SMS template '{}' skipped — Twilio credentials not configured", templateKey);
            messageLogService.logOutbound(templateKey, automationKey, phoneNumber, body, false, "not_configured", null);
            return SmsSendResult.skipped("not_configured");
        }

        try {
            String twilioMessageSid = client.send(config, phoneNumber, body);
            messageLogService.logOutbound(templateKey, automationKey, phoneNumber, body, true, null, twilioMessageSid);
            return SmsSendResult.ok();
        } catch (Exception e) {
            log.warn("SMS template '{}' send failed (caller unaffected): {}", templateKey, e.getMessage());
            messageLogService.logOutbound(templateKey, automationKey, phoneNumber, body, false, "send_failed", null);
            return SmsSendResult.skipped("send_failed");
        }
    }

    /** A human-typed reply from a manager/owner, bypassing templates and automation/consent
     * gating entirely — see openspec/changes/lead-followup-and-manager-inbox design.md D9. A
     * manager replying to a customer who just texted the salon is a direct conversational reply,
     * not a marketing send, so it's sendable regardless of {@code sms_marketing_consent}. */
    public SmsSendResult sendManual(String phoneNumber, String body) {
        TwilioSmsConfig config = configService.get();
        if (!config.isConfigured()) {
            log.info("Manual reply skipped — Twilio credentials not configured");
            messageLogService.logOutbound(null, null, phoneNumber, body, false, "not_configured", null);
            return SmsSendResult.skipped("not_configured");
        }

        try {
            String twilioMessageSid = client.send(config, phoneNumber, body);
            messageLogService.logOutbound(null, null, phoneNumber, body, true, null, twilioMessageSid);
            return SmsSendResult.ok();
        } catch (Exception e) {
            log.warn("Manual reply send failed (caller unaffected): {}", e.getMessage());
            messageLogService.logOutbound(null, null, phoneNumber, body, false, "send_failed", null);
            return SmsSendResult.skipped("send_failed");
        }
    }
}
