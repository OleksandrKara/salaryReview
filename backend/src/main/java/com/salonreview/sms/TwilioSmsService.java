package com.salonreview.sms;

import com.salonreview.domain.SmsMessage;
import com.salonreview.domain.SmsMessageMedia;
import com.salonreview.domain.TwilioSmsConfig;
import com.salonreview.repo.BlockedNumberRepository;
import com.salonreview.util.PhoneNumbers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
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

    private final SmsMessageTemplateService templateService;
    private final TwilioSmsConfigService configService;
    private final SmsConsentRepository consentRepository;
    private final SmsAutomationService automationService;
    private final SmsMessageLogService messageLogService;
    private final TwilioSmsClient client;
    private final BlockedNumberRepository blockedNumberRepository;
    private final SmsMediaService mediaService;

    public TwilioSmsService(SmsMessageTemplateService templateService, TwilioSmsConfigService configService,
                            SmsConsentRepository consentRepository, SmsAutomationService automationService,
                            SmsMessageLogService messageLogService, TwilioSmsClient client,
                            BlockedNumberRepository blockedNumberRepository, SmsMediaService mediaService) {
        this.templateService = templateService;
        this.configService = configService;
        this.consentRepository = consentRepository;
        this.automationService = automationService;
        this.messageLogService = messageLogService;
        this.client = client;
        this.blockedNumberRepository = blockedNumberRepository;
        this.mediaService = mediaService;
    }

    /** True if a manager has blocked this number from the conversation view (see V61) — checked
     * first in both {@link #sendTemplated} and {@link #sendManual} so a block silences every
     * outbound SMS, automated or manual, from this one place rather than needing a special case
     * in each automation. */
    private boolean isBlocked(String phoneNumber) {
        return blockedNumberRepository.existsById(PhoneNumbers.normalize(phoneNumber));
    }

    public SmsSendResult sendTemplated(Long businessId, String templateKey, String phoneNumber, Map<String, String> variables) {
        SmsMessageTemplateCatalog.TemplateDefault template = templateService.describe(templateKey);
        if (template == null) {
            // Nothing to render and no automationKey to attribute this to — logged as a bare
            // attempt so it's still visible in the activity view, matching every other outcome.
            messageLogService.logOutbound(businessId, templateKey, null, phoneNumber, "", false, "unknown_template", null);
            return SmsSendResult.skipped("unknown_template");
        }

        TwilioSmsConfig config = configService.get(businessId);
        Map<String, String> renderVars = new java.util.HashMap<>(variables == null ? Map.of() : variables);
        // Every catalog template may reference {{sender}} — resolved once here so callers don't
        // each need to fetch TwilioSmsConfig just to pass it through.
        renderVars.putIfAbsent("sender", config.getSenderName());
        String body = templateService.render(businessId, templateKey, renderVars);
        String automationKey = template.automationKey();

        if (isBlocked(phoneNumber)) {
            log.info("SMS template '{}' skipped — number is blocked", templateKey);
            messageLogService.logOutbound(businessId, templateKey, automationKey, phoneNumber, body, false, "blocked", null);
            return SmsSendResult.skipped("blocked");
        }

        if (!automationService.isEnabled(businessId, automationKey)) {
            log.info("SMS template '{}' skipped — automation '{}' is disabled", templateKey, automationKey);
            messageLogService.logOutbound(businessId, templateKey, automationKey, phoneNumber, body, false, "automation_disabled", null);
            return SmsSendResult.skipped("automation_disabled");
        }

        if (template.messageClass() == SmsMessageClass.MARKETING && !consentRepository.hasMarketingConsent(phoneNumber)) {
            log.info("SMS template '{}' skipped — no marketing consent for this contact", templateKey);
            messageLogService.logOutbound(businessId, templateKey, automationKey, phoneNumber, body, false, "no_consent", null);
            return SmsSendResult.skipped("no_consent");
        }

        if (!config.isConfigured()) {
            log.info("SMS template '{}' skipped — Twilio credentials not configured", templateKey);
            messageLogService.logOutbound(businessId, templateKey, automationKey, phoneNumber, body, false, "not_configured", null);
            return SmsSendResult.skipped("not_configured");
        }

        try {
            String twilioMessageSid = client.send(config, phoneNumber, body);
            messageLogService.logOutbound(businessId, templateKey, automationKey, phoneNumber, body, true, null, twilioMessageSid);
            return SmsSendResult.ok();
        } catch (Exception e) {
            log.warn("SMS template '{}' send failed (caller unaffected): {}", templateKey, e.getMessage());
            messageLogService.logOutbound(businessId, templateKey, automationKey, phoneNumber, body, false, "send_failed", null);
            return SmsSendResult.skipped("send_failed");
        }
    }

    /** A human-typed reply from a manager/owner, bypassing templates and automation/consent
     * gating entirely — see openspec/changes/lead-followup-and-manager-inbox design.md D9. A
     * manager replying to a customer who just texted the salon is a direct conversational reply,
     * not a marketing send, so it's sendable regardless of {@code sms_marketing_consent}. */
    public SmsSendResult sendManual(Long businessId, String phoneNumber, String body) {
        if (isBlocked(phoneNumber)) {
            log.info("Manual reply skipped — number is blocked");
            messageLogService.logOutbound(businessId, null, null, phoneNumber, body, false, "blocked", null);
            return SmsSendResult.skipped("blocked");
        }

        TwilioSmsConfig config = configService.get(businessId);
        if (!config.isConfigured()) {
            log.info("Manual reply skipped — Twilio credentials not configured");
            messageLogService.logOutbound(businessId, null, null, phoneNumber, body, false, "not_configured", null);
            return SmsSendResult.skipped("not_configured");
        }

        try {
            String twilioMessageSid = client.send(config, phoneNumber, body);
            messageLogService.logOutbound(businessId, null, null, phoneNumber, body, true, null, twilioMessageSid);
            return SmsSendResult.ok();
        } catch (Exception e) {
            log.warn("Manual reply send failed (caller unaffected): {}", e.getMessage());
            messageLogService.logOutbound(businessId, null, null, phoneNumber, body, false, "send_failed", null);
            return SmsSendResult.skipped("send_failed");
        }
    }

    /** Same as {@link #sendManual}, with one or more photo attachments — a manager sending an MMS
     * reply. Reserve-then-finalize (same pattern as {@code CheckoutReviewReplyService}'s click-token
     * reservation): the {@code sms_message} row must exist with a real id before each attachment's
     * public {@code /api/public/sms-media/{token}} URL can be constructed and handed to Twilio, so
     * the row is saved first (status {@code NOT_SENT}/"pending"), then updated once the send outcome
     * is known. {@code files} is never empty — a caller with no photos should use {@link #sendManual}
     * instead. */
    public SmsSendResult sendManualWithMedia(Long businessId, String phoneNumber, String body, List<MultipartFile> files) throws IOException {
        String safeBody = body == null ? "" : body;
        if (isBlocked(phoneNumber)) {
            log.info("Manual MMS reply skipped — number is blocked");
            messageLogService.logOutbound(businessId, null, null, phoneNumber, safeBody, false, "blocked", null);
            return SmsSendResult.skipped("blocked");
        }

        TwilioSmsConfig config = configService.get(businessId);
        if (!config.isConfigured()) {
            log.info("Manual MMS reply skipped — Twilio credentials not configured");
            messageLogService.logOutbound(businessId, null, null, phoneNumber, safeBody, false, "not_configured", null);
            return SmsSendResult.skipped("not_configured");
        }

        SmsMessage reserved = messageLogService.logOutbound(businessId, null, null, phoneNumber, safeBody, false, "pending", null);
        List<String> mediaUrls = new ArrayList<>();
        for (MultipartFile file : files) {
            SmsMessageMedia media = mediaService.store(reserved.getId(), file.getContentType(), file.getBytes());
            mediaUrls.add(mediaService.publicUrl(media));
        }

        try {
            String twilioMessageSid = client.send(config, phoneNumber, safeBody, mediaUrls);
            reserved.setStatus("SENT");
            reserved.setReason(null);
            reserved.setTwilioMessageSid(twilioMessageSid);
            messageLogService.save(reserved);
            return SmsSendResult.ok();
        } catch (Exception e) {
            log.warn("Manual MMS reply send failed (caller unaffected): {}", e.getMessage());
            reserved.setStatus("NOT_SENT");
            reserved.setReason("send_failed");
            messageLogService.save(reserved);
            return SmsSendResult.skipped("send_failed");
        }
    }
}
