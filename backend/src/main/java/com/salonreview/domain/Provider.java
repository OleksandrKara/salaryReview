package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "providers")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class Provider {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "commission_rate", nullable = false, precision = 5, scale = 4)
    private BigDecimal commissionRate;

    @Column(name = "card_tip_fee_rate", nullable = false, precision = 5, scale = 4)
    private BigDecimal cardTipFeeRate;

    @Column(nullable = false)
    private boolean active;

    /** Square team-member IDs that map to this person; many when a stylist has had several accounts. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "provider_square_member", joinColumns = @JoinColumn(name = "provider_id"))
    @Column(name = "square_team_member_id")
    @Builder.Default
    private Set<String> squareTeamMemberIds = new HashSet<>();
}
