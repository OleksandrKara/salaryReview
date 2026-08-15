package com.salonreview.service;

import com.salonreview.domain.AppUser;
import com.salonreview.domain.Business;
import com.salonreview.domain.BusinessMembership;
import com.salonreview.domain.Role;
import com.salonreview.repo.AppUserRepository;
import com.salonreview.repo.BusinessMembershipRepository;
import com.salonreview.repo.BusinessRepository;
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

    public BusinessProvisioningService(BusinessRepository businesses, AppUserRepository users,
                                        BusinessMembershipRepository memberships, PasswordEncoder encoder) {
        this.businesses = businesses;
        this.users = users;
        this.memberships = memberships;
        this.encoder = encoder;
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

        return business;
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required");
        }
        return value.trim();
    }
}
