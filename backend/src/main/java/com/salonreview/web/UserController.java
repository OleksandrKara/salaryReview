package com.salonreview.web;

import com.salonreview.domain.AppUser;
import com.salonreview.domain.BusinessMembership;
import com.salonreview.domain.Provider;
import com.salonreview.domain.Role;
import com.salonreview.repo.AppUserRepository;
import com.salonreview.repo.BusinessMembershipRepository;
import com.salonreview.repo.ProviderRepository;
import com.salonreview.repo.SopAcknowledgmentRepository;
import com.salonreview.service.ProviderDirectory;
import com.salonreview.square.SquareClient;
import com.salonreview.square.SquareClient.TeamMember;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Owner-only user management. Accounts are created/invited here (no open self-signup). A PROVIDER
 * account must be linked to a provider person; OWNER/MANAGER accounts must not. The response never
 * includes the password hash.
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final AppUserRepository users;
    private final ProviderRepository providers;
    private final ProviderDirectory directory;
    private final SquareClient square;
    private final PasswordEncoder encoder;
    private final SopAcknowledgmentRepository sopAcks;
    private final BusinessMembershipRepository memberships;
    private final com.salonreview.config.CurrentBusinessContext currentBusinessContext;

    public UserController(AppUserRepository users, ProviderRepository providers,
                          ProviderDirectory directory, SquareClient square, PasswordEncoder encoder,
                          SopAcknowledgmentRepository sopAcks, BusinessMembershipRepository memberships,
                          com.salonreview.config.CurrentBusinessContext currentBusinessContext) {
        this.users = users;
        this.providers = providers;
        this.directory = directory;
        this.square = square;
        this.encoder = encoder;
        this.sopAcks = sopAcks;
        this.memberships = memberships;
        this.currentBusinessContext = currentBusinessContext;
    }

    @GetMapping
    public List<UserView> list() {
        return users.findAllByBusinessIdOrderByUsernameAsc(currentBusinessContext.id())
                .stream().map(UserView::of).toList();
    }

    /**
     * The salon's Square team roster, with a suggested app role (from {@code is_owner} / job title)
     * and whether an account already exists — feeds the "import from Square" add-user flow. The owner
     * still confirms; we never auto-grant access from Square.
     */
    @GetMapping("/square-roster")
    public List<RosterEntry> squareRoster() {
        List<AppUser> existing = users.findAllByBusinessIdOrderByUsernameAsc(currentBusinessContext.id());
        Set<String> linkedMemberIds = existing.stream()
                .map(AppUser::getSquareTeamMemberId).filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        Set<Long> linkedProviderIds = existing.stream()
                .map(AppUser::getProviderId).filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());

        return square.activeTeamMembers().stream().map(tm -> {
            Role suggested = suggestRole(tm);
            Provider provider = suggested == Role.PROVIDER
                    ? providers.findBySquareTeamMemberId(tm.id()).orElse(null) : null;
            Long providerId = provider != null ? provider.getId() : null;
            boolean hasAccount = linkedMemberIds.contains(tm.id())
                    || (providerId != null && linkedProviderIds.contains(providerId));
            return new RosterEntry(tm.id(), tm.fullName(), tm.emailAddress(), tm.jobTitle(),
                    tm.owner(), suggested, providerId, hasAccount);
        }).toList();
    }

    /** is_owner → OWNER; a "manager" job title → MANAGER; everyone else (nail techs) → PROVIDER. */
    private static Role suggestRole(TeamMember tm) {
        if (tm.owner()) return Role.OWNER;
        String title = tm.jobTitle() == null ? "" : tm.jobTitle().toLowerCase();
        if (title.contains("owner")) return Role.OWNER;
        if (title.contains("manager")) return Role.MANAGER;
        return Role.PROVIDER;
    }

    @PostMapping
    @Transactional
    public UserView create(@RequestBody CreateRequest req) {
        if (req.username() == null || req.username().isBlank()
                || req.password() == null || req.password().isBlank() || req.role() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "username, password and role are required");
        }
        if (users.existsByBusinessIdAndUsername(currentBusinessContext.id(), req.username())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already taken");
        }
        // A provider imported from Square may not have a Provider row yet (no bookings) — provision it.
        Long providerId = req.providerId();
        if (req.role() == Role.PROVIDER && providerId == null && req.squareTeamMemberId() != null) {
            String name = req.name() != null && !req.name().isBlank() ? req.name() : req.username();
            providerId = directory.resolveOrCreate(req.squareTeamMemberId(), name).getId();
        }
        providerId = validateProviderLink(req.role(), providerId, null);
        Long businessId = currentBusinessContext.id();
        AppUser saved = users.save(AppUser.builder()
                .businessId(businessId)
                .username(req.username().trim())
                .passwordHash(encoder.encode(req.password()))
                .role(req.role())
                .providerId(providerId)
                .squareTeamMemberId(req.squareTeamMemberId())
                .email(req.email())
                .active(true)
                .build());
        // Without this row JpaUserDetailsService fails loudly on the new account's first login —
        // every app_user needs exactly one business_membership row (design.md D3).
        memberships.save(BusinessMembership.builder()
                .businessId(businessId).userId(saved.getId()).role(req.role()).build());
        return UserView.of(saved);
    }

    @PatchMapping("/{id}")
    @Transactional
    public UserView update(@PathVariable Long id, @RequestBody UpdateRequest req) {
        AppUser u = users.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such user"));
        if (req.role() != null) u.setRole(req.role());
        if (req.active() != null) u.setActive(req.active());
        if (req.password() != null && !req.password().isBlank()) u.setPasswordHash(encoder.encode(req.password()));
        // Re-evaluate the provider link against the effective role (after any role change above).
        u.setProviderId(validateProviderLink(u.getRole(),
                req.providerId() != null ? req.providerId() : u.getProviderId(), u.getId()));
        return UserView.of(users.save(u));
    }

    @DeleteMapping("/{id}")
    @Transactional
    public void delete(@PathVariable Long id) {
        AppUser u = users.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such user"));
        // Never lock the salon out: refuse to remove the last active owner.
        if (u.getRole() == Role.OWNER && u.isActive() && activeOwners() <= 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot remove the last active owner");
        }
        // Clear this user's SOP acknowledgments first — that FK has no ON DELETE cascade, so leaving
        // them would fail the delete with a constraint violation (previously surfaced as a 500).
        sopAcks.deleteByUserId(id);
        users.delete(u);
    }

    /** A provider account needs a real, unlinked provider; owner/manager accounts must have none. */
    private Long validateProviderLink(Role role, Long providerId, Long currentUserId) {
        if (role != Role.PROVIDER) return null;
        if (providerId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A provider account must be linked to a provider");
        }
        if (!providers.existsById(providerId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No such provider");
        }
        boolean takenByOther = users.findAllByBusinessIdOrderByUsernameAsc(currentBusinessContext.id()).stream()
                .anyMatch(other -> providerId.equals(other.getProviderId())
                        && !other.getId().equals(currentUserId));
        if (takenByOther) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That provider already has an account");
        }
        return providerId;
    }

    private long activeOwners() {
        return users.findAllByBusinessIdOrderByUsernameAsc(currentBusinessContext.id()).stream()
                .filter(u -> u.getRole() == Role.OWNER && u.isActive()).count();
    }

    public record CreateRequest(String username, String password, Role role, Long providerId,
                                String squareTeamMemberId, String email, String name) {}
    public record UpdateRequest(Role role, Boolean active, String password, Long providerId) {}

    public record UserView(Long id, String username, Role role, Long providerId, boolean active,
                           String squareTeamMemberId, String email) {
        static UserView of(AppUser u) {
            return new UserView(u.getId(), u.getUsername(), u.getRole(), u.getProviderId(), u.isActive(),
                    u.getSquareTeamMemberId(), u.getEmail());
        }
    }

    /** A Square team member as a candidate account, with a suggested role and account status. */
    public record RosterEntry(String teamMemberId, String name, String email, String jobTitle,
                              boolean isOwner, Role suggestedRole, Long providerId, boolean hasAccount) {}
}
