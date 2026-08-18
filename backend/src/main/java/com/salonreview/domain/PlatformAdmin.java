package com.salonreview.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Design.md D4 — a narrow, additive flag: "this user manages every business's onboarding," not a
 * business-scoped role and not a {@link Role} enum value. See V107's own migration comment for why
 * this exists as its own table rather than folding into {@link AppUser}.
 */
@Entity
@Table(name = "platform_admin")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class PlatformAdmin {

    @Id
    @jakarta.persistence.Column(name = "user_id")
    private Long userId;
}
