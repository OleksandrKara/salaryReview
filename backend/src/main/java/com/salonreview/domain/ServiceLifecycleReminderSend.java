package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One row per (business, automation, customer, triggering procedure date) a lifecycle-reminder
 * automation has already considered — see V126. Shared shape across every "N days after a
 * qualifying service" automation (today: {@code touchup_reminder}), not one table per automation.
 */
@Entity
@Table(name = "service_lifecycle_reminder_send",
       uniqueConstraints = @UniqueConstraint(columnNames = {"business_id", "automation_key", "square_customer_id", "trigger_service_date"}))
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class ServiceLifecycleReminderSend {

    public static final String STATE_SENT = "SENT";
    public static final String STATE_SKIPPED_ALREADY_DONE = "SKIPPED_ALREADY_DONE";
    public static final String STATE_SKIPPED_UNRESOLVED = "SKIPPED_UNRESOLVED";
    public static final String STATE_SKIPPED_NEGATIVE_FEEDBACK = "SKIPPED_NEGATIVE_FEEDBACK";
    public static final String STATE_NOT_SENT = "NOT_SENT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_id", nullable = false)
    private Long businessId;

    @Column(name = "automation_key", nullable = false)
    private String automationKey;

    @Column(name = "square_customer_id", nullable = false)
    private String squareCustomerId;

    @Column(name = "trigger_service_date", nullable = false)
    private LocalDate triggerServiceDate;

    @Column(name = "phone_number")
    private String phoneNumber;

    @Column(name = "customer_name")
    private String customerName;

    /** {@link #STATE_SENT} or a skip reason — {@link #STATE_NOT_SENT} carries whatever
     * {@code TwilioSmsService.SmsSendResult#reason()} returned (e.g. {@code not_configured},
     * {@code automation_disabled}), uppercased, rather than a fixed enum of every possible send
     * failure — see {@code TouchupReminderScheduler}. */
    @Column(name = "state", nullable = false)
    private String state;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
