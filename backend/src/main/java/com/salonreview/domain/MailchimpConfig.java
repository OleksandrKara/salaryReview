package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Per-business runtime config for outbound marketing email via Mailchimp (see V128) — same shape
 * and null/blank-means-off convention as {@link TwilioSmsConfig}. Owner-editable at
 * {@code /api/owner/settings/mailchimp}.
 */
@Entity
@Table(name = "mailchimp_config")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class MailchimpConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_id", nullable = false, unique = true)
    private Long businessId;

    /** Includes the datacenter/server-prefix suffix (e.g. {@code "abc123...-us21"}) — Mailchimp's
     * API base URL is built from that suffix, not a separately-entered field. */
    @Column(name = "api_key")
    private String apiKey;

    @Column(name = "audience_id")
    private String audienceId;

    @Column(name = "from_name")
    private String fromName;

    @Column(name = "reply_to_email")
    private String replyToEmail;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private String updatedBy;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    /** True once every field needed to actually send a campaign/automation email is present. */
    public boolean isConfigured() {
        return notBlank(apiKey) && notBlank(audienceId) && notBlank(fromName) && notBlank(replyToEmail);
    }

    /** The datacenter/server prefix Mailchimp's API base URL is built from — the substring after
     * the last {@code "-"} in the API key (e.g. {@code "us21"} from {@code "abc123...-us21"}).
     * {@code null} if the key isn't set or doesn't contain the expected suffix. */
    public String serverPrefix() {
        if (apiKey == null) return null;
        int dash = apiKey.lastIndexOf('-');
        return dash < 0 || dash == apiKey.length() - 1 ? null : apiKey.substring(dash + 1);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
