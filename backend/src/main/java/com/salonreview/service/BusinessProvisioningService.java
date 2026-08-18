package com.salonreview.service;

import com.salonreview.domain.AppUser;
import com.salonreview.domain.Business;
import com.salonreview.domain.BusinessMembership;
import com.salonreview.domain.Role;
import com.salonreview.domain.TelegramNotificationConfig;
import com.salonreview.domain.TwilioSmsConfig;
import com.salonreview.repo.AppUserRepository;
import com.salonreview.repo.BusinessMembershipRepository;
import com.salonreview.repo.BusinessRepository;
import com.salonreview.repo.TelegramNotificationConfigRepository;
import com.salonreview.repo.TwilioSmsConfigRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Phase 5.1 — creates a new tenant: a {@link Business} row plus its first OWNER login, mirroring
 * exactly what {@link com.salonreview.config.OwnerBootstrap} does for Business A at app startup,
 * except triggered by an admin action instead of an empty-table check. {@code username} stays
 * globally unique across every business (not just this one) — same invariant
 * {@code AppUserRepository.findByUsername}'s own doc comment already documents: login has no
 * business-picker yet, so two businesses can't share a username until one exists.
 */
@Service
public class BusinessProvisioningService {

    private final BusinessRepository businesses;
    private final AppUserRepository users;
    private final BusinessMembershipRepository memberships;
    private final PasswordEncoder encoder;
    private final TelegramNotificationConfigRepository telegramConfigs;
    private final TwilioSmsConfigRepository twilioConfigs;

    public BusinessProvisioningService(BusinessRepository businesses, AppUserRepository users,
                                        BusinessMembershipRepository memberships, PasswordEncoder encoder,
                                        TelegramNotificationConfigRepository telegramConfigs,
                                        TwilioSmsConfigRepository twilioConfigs) {
        this.businesses = businesses;
        this.users = users;
        this.memberships = memberships;
        this.encoder = encoder;
        this.telegramConfigs = telegramConfigs;
        this.twilioConfigs = twilioConfigs;
    }

    public List<Business> list() {
        return businesses.findAll();
    }

    @Transactional
    public Business create(String name, String shortCode, String timezone,
                            String ownerUsername, String ownerPassword) {
        String trimmedName = requireNonBlank(name, "name");
        String trimmedShortCode = requireNonBlank(shortCode, "shortCode").toLowerCase();
        String trimmedTimezone = requireNonBlank(timezone, "timezone");
        String trimmedUsername = requireNonBlank(ownerUsername, "ownerUsername");
        requireNonBlank(ownerPassword, "ownerPassword");

        if (businesses.findByShortCode(trimmedShortCode).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "shortCode '" + trimmedShortCode + "' is already in use");
        }
        if (users.findByUsername(trimmedUsername).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "username '" + trimmedUsername + "' is already in use");
        }

        Business business = businesses.save(Business.builder()
                .name(trimmedName)
                .shortCode(trimmedShortCode)
                .timezone(trimmedTimezone)
                .active(true)
                .build());

        AppUser owner = users.save(AppUser.builder()
                .businessId(business.getId())
                .username(trimmedUsername)
                .passwordHash(encoder.encode(ownerPassword))
                .role(Role.OWNER)
                .active(true)
                .build());

        memberships.save(BusinessMembership.builder()
                .businessId(business.getId())
                .userId(owner.getId())
                .role(Role.OWNER)
                .build());

        // 2026-08-18 live incident: a business created without these rows 500'd the instant its
        // owner opened Settings > Telegram or Settings > SMS — both services assume their row
        // already exists (see TelegramConfigService#get/TwilioSmsConfigService#get) and have no
        // "not set up yet" fallback of their own. Empty (all-null-credential) rows are this
        // feature's own correct "off" representation everywhere else it's read (e.g.
        // TelegramNotificationService's own null-check-and-skip) — this doesn't turn anything on,
        // it just makes "not configured yet" reachable without a crash. RAG intentionally gets no
        // such row (tasks.md 7.4) — its own "off" representation is the absence of an active
        // config row, not an empty one; see RagConfigService#findActive.
        telegramConfigs.save(TelegramNotificationConfig.builder().businessId(business.getId()).build());
        twilioConfigs.save(TwilioSmsConfig.builder().businessId(business.getId()).build());

        return business;
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required");
        }
        return value.trim();
    }
}
