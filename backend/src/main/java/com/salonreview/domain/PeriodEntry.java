package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "period_entries", uniqueConstraints =
    @UniqueConstraint(columnNames = {"provider_id", "pay_period_id"}))
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class PeriodEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "provider_id", nullable = false)
    private Provider provider;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pay_period_id", nullable = false)
    private PayPeriod payPeriod;

    @Column(nullable = false)
    private int procedures;

    @Column(name = "card_total", nullable = false, precision = 10, scale = 2)
    private BigDecimal cardTotal;

    @Column(name = "cash_total", nullable = false, precision = 10, scale = 2)
    private BigDecimal cashTotal;

    @Column(name = "card_tips", nullable = false, precision = 10, scale = 2)
    private BigDecimal cardTips;

    @Column(name = "adjustments_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal adjustmentsAmount;

    @Column(name = "adjustments_note")
    private String adjustmentsNote;
}
