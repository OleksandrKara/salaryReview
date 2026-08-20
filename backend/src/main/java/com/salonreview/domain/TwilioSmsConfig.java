package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Per-business runtime config for outbound SMS via Twilio (see V46, business-scoped by V95).
 * Owner-editable at {@code /api/owner/settings/sms}; mani and akluxnails-home never see these
 * credentials — they call {@code POST /api/internal/notifications/sms/send} instead. Every
 * automation/scheduler call site with no session resolves {@code businessId} via
 * {@link com.salonreview.repo.BusinessRepository#legacySmsBusiness} — see
 * {@link com.salonreview.sms.TwilioSmsConfigService#getForAutomation}.
 */
@Entity
@Table(name = "twilio_sms_config")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class TwilioSmsConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_id", nullable = false, unique = true)
    private Long businessId;

    @Column(name = "account_sid")
    private String accountSid;

    @Column(name = "api_key")
    private String apiKey;

    @Column(name = "api_secret")
    private String apiSecret;

    @Column(name = "from_phone_number")
    private String fromPhoneNumber;

    /** Who every automated (and AI-drafted) SMS signs as — e.g. the "It's {{sender}} from
     * AK.LUX.NAILS 💛" greeting and the "-{{sender}}" signature every template in {@code
     * SmsMessageTemplateCatalog} can reference. Defaults to "Lucy" at the DB level (see V115) so
     * an existing business's wording never changes until the owner deliberately edits it; {@code
     * @Builder.Default} mirrors that default for any Java-side construction (tests, a
     * not-yet-persisted new row) that doesn't set it explicitly. */
    @Builder.Default
    @Column(name = "sender_name", nullable = false)
    private String senderName = "Lucy";

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private String updatedBy;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    /** True once all three credential fields needed to call Twilio are present. */
    public boolean isConfigured() {
        return notBlank(accountSid) && notBlank(apiKey) && notBlank(apiSecret) && notBlank(fromPhoneNumber);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
