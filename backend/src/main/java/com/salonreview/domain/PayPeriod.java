package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "pay_periods", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"year", "month", "half"}),
    @UniqueConstraint(columnNames = {"business_id", "year", "month", "half"})})
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class PayPeriod {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_id", nullable = false)
    private Long businessId;

    @Column(nullable = false)
    private int year;

    @Column(nullable = false)
    private int month;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Half half;

    @Column(nullable = false)
    private String label;
}
