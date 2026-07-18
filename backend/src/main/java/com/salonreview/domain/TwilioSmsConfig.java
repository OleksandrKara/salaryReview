package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Single-row runtime config for outbound SMS via Twilio (see V46). Owner-editable at
 * {@code /api/owner/settings/sms}; mani and akluxnails-home never see these credentials — they
 * call {@code POST /api/internal/notifications/sms/send} instead.
 */
@Entity
@Table(name = "twilio_sms_config")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class TwilioSmsConfig {

    @Id
    @Builder.Default
    private Boolean id = Boolean.TRUE;

    @Column(name = "account_sid")
    private String accountSid;

    @Column(name = "api_key")
    private String apiKey;

    @Column(name = "api_secret")
    private String apiSecret;

    @Column(name = "from_phone_number")
    private String fromPhoneNumber;

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
