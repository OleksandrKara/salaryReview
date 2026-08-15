package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * An owner/manager override for no-show fee tracking. No-shows and their paid $25 cancellation fees are
 * derived live from Square; this records only the exceptions, keyed by the no-show's Square booking id:
 * <ul>
 *   <li>{@code SUPPRESS} — do not credit an auto-detected fee (false positive / disputed).</li>
 *   <li>{@code CONFIRM} — credit {@link #providerId} the {@link #amount} for a fee collected off-signal
 *       (cash / quick-sale, odd label, or paid &gt; 2 months later). Self-contained so it applies without
 *       re-reading Square; lands in {@link #feePaidDate}'s month.</li>
 * </ul>
 * ({@code created_at} is DB-managed.)
 */
@Entity
@Table(name = "no_show_fee_override")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class NoShowFeeOverride {

    public static final String CONFIRM = "CONFIRM";
    public static final String SUPPRESS = "SUPPRESS";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_id", nullable = false)
    private Long businessId;

    @Column(name = "square_booking_id", nullable = false, unique = true)
    private String squareBookingId;

    @Column(nullable = false)
    private String kind;

    @Column(name = "provider_id")
    private Long providerId;

    @Column(name = "customer_name")
    private String customerName;

    @Column(name = "no_show_date")
    private LocalDate noShowDate;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(name = "fee_paid_date")
    private LocalDate feePaidDate;

    @Column
    private String note;

    @Column(name = "created_by")
    private String createdBy;
}
