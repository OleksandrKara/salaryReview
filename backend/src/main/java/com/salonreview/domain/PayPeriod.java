package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "pay_periods", uniqueConstraints =
    @UniqueConstraint(columnNames = {"year", "month", "half"}))
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class PayPeriod {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

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
