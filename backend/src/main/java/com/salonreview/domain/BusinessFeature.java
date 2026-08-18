package com.salonreview.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Phase 4.3 (multi-tenant-salon-platform) — per-business on/off for the optional AI/RAG feature
 * set. See V108's own migration comment for the feature_key values and the seeding rationale.
 * A missing row for a (businessId, featureKey) pair means disabled, same as an explicit
 * {@code enabled=false} row — see {@link com.salonreview.config.BusinessFeatureService}.
 */
@Entity
@Table(name = "business_feature")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class BusinessFeature {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_id")
    private Long businessId;

    @Column(name = "feature_key")
    private String featureKey;

    private boolean enabled;
}
