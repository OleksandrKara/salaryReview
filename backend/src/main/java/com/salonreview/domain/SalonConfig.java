package com.salonreview.domain;

import com.salonreview.commission.CommissionConfig;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "salon_config")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class SalonConfig {

    @Id
    private Integer id;

    @Column(name = "business_id", nullable = false, unique = true)
    private Long businessId;

    @Column(name = "owner_short_name", nullable = false)
    private String ownerShortName;

    @Column(name = "tier_service_threshold", nullable = false)
    private int tierServiceThreshold;

    @Column(name = "service_price_cutoff", nullable = false, precision = 10, scale = 2)
    private BigDecimal servicePriceCutoff;

    @Column(name = "base_commission_rate", nullable = false, precision = 5, scale = 4)
    private BigDecimal baseCommissionRate;

    @Column(name = "tier_commission_rate", nullable = false, precision = 5, scale = 4)
    private BigDecimal tierCommissionRate;

    @Column(name = "card_tip_fee_rate", nullable = false, precision = 5, scale = 4)
    private BigDecimal cardTipFeeRate;

    /** Build the commission engine's config from the stored settings. */
    public CommissionConfig toCommissionConfig() {
        return new CommissionConfig(tierServiceThreshold, baseCommissionRate, tierCommissionRate, cardTipFeeRate);
    }
}
