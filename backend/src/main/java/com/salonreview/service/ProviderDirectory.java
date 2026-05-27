package com.salonreview.service;

import com.salonreview.domain.Provider;
import com.salonreview.domain.SalonConfig;
import com.salonreview.repo.ProviderRepository;
import com.salonreview.repo.SalonConfigRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * Resolves a Square team-member ID to a provider (person), auto-creating one on first sight so the
 * salon doesn't have to pre-register staff. Multiple team-member IDs can later be pointed at the same
 * provider (a manual merge) so a rehired stylist's months combine.
 */
@Service
public class ProviderDirectory {

    private final ProviderRepository providers;
    private final SalonConfigRepository salonConfig;

    public ProviderDirectory(ProviderRepository providers, SalonConfigRepository salonConfig) {
        this.providers = providers;
        this.salonConfig = salonConfig;
    }

    @Transactional
    public Provider resolveOrCreate(String teamMemberId, String name) {
        return providers.findBySquareTeamMemberId(teamMemberId)
                .orElseGet(() -> create(teamMemberId, name));
    }

    private Provider create(String teamMemberId, String name) {
        SalonConfig cfg = salonConfig.findById(1)
                .orElseThrow(() -> new IllegalStateException("Salon config with id=1 is missing"));
        Set<String> ids = new HashSet<>();
        ids.add(teamMemberId);
        Provider p = Provider.builder()
                .name(name)
                .displayName(name)
                .commissionRate(cfg.getBaseCommissionRate())
                .cardTipFeeRate(cfg.getCardTipFeeRate())
                .active(true)
                .squareTeamMemberIds(ids)
                .build();
        return providers.save(p);
    }

    /** Point a second Square team-member ID at an existing provider (merge a duplicate account). */
    @Transactional
    public Provider linkTeamMember(Long providerId, String teamMemberId) {
        Provider p = providers.findById(providerId)
                .orElseThrow(() -> new NoSuchElementException("Provider " + providerId + " not found"));
        p.getSquareTeamMemberIds().add(teamMemberId);
        return providers.save(p);
    }
}
