package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A confirmed draw-down of a {@link PrepaidPackage}: an owner/manager confirmed a real Square booking
 * (service) was performed against the package. The provider is paid on {@link #menuPrice} (gross,
 * like card) attributed to {@link #serviceDate}; {@link #counts} is whether it clears the tier cutoff.
 * ({@code confirmed_at} is DB-managed.)
 */
@Entity
@Table(name = "prepaid_redemption")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class PrepaidRedemption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "package_id", nullable = false)
    private Long packageId;

    @Column(name = "square_booking_id", nullable = false)
    private String squareBookingId;

    @Column(name = "service_variation_id", nullable = false)
    private String serviceVariationId;

    @Column(name = "service_name")
    private String serviceName;

    @Column(name = "service_date", nullable = false)
    private LocalDate serviceDate;

    @Column(name = "menu_price", nullable = false)
    private BigDecimal menuPrice;

    @Column(nullable = false)
    private boolean counts;

    @Column(name = "confirmed_by")
    private String confirmedBy;
}
