package com.salonreview.web;

import com.salonreview.config.AppUserPrincipal;
import com.salonreview.config.CurrentBusinessContextFilter;
import com.salonreview.domain.Business;
import com.salonreview.repo.BusinessMembershipRepository;
import com.salonreview.repo.BusinessRepository;
import com.salonreview.repo.PlatformAdminRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Phase 6.1/6.2 (design.md D12): lets a user with access to more than one business — today, in
 * practice, only the platform_admin — change which business every subsequent request in this
 * session acts on, without re-authenticating. See {@link CurrentBusinessContextFilter}'s own doc
 * for how the session attribute this sets actually takes effect.
 *
 * <p>Deliberately does NOT change {@link AppUserPrincipal#getRole()} — a platform_admin switching
 * to another business keeps acting with their own (OWNER) authority there, which is exactly the
 * "you, managing every business's onboarding" power design.md D4 describes. A genuinely different
 * per-business role for the same login (e.g. MANAGER at business A, PROVIDER at business B) isn't
 * a case that exists today (every real account has exactly one membership row) — {@link
 * com.salonreview.config.JpaUserDetailsService}'s own doc comment already flags that as unsupported
 * until it's a real requirement, not a gap silently introduced here.
 */
@RestController
@RequestMapping("/api/business")
public class BusinessSwitchController {

    private final BusinessRepository businesses;
    private final BusinessMembershipRepository memberships;
    private final PlatformAdminRepository platformAdmins;

    public BusinessSwitchController(BusinessRepository businesses, BusinessMembershipRepository memberships,
                                     PlatformAdminRepository platformAdmins) {
        this.businesses = businesses;
        this.memberships = memberships;
        this.platformAdmins = platformAdmins;
    }

    @PostMapping("/switch")
    public Map<String, Object> switchBusiness(@RequestBody SwitchRequest body,
                                               @AuthenticationPrincipal AppUserPrincipal principal,
                                               HttpServletRequest request) {
        if (body.businessId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "businessId is required");
        }
        Business target = businesses.findById(body.businessId())
                .filter(Business::isActive)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such business"));

        boolean allowed = platformAdmins.existsById(principal.getUserId())
                || memberships.existsByUserIdAndBusinessId(principal.getUserId(), target.getId());
        if (!allowed) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No access to that business");
        }

        request.getSession(true).setAttribute(
                CurrentBusinessContextFilter.ACTIVE_BUSINESS_SESSION_ATTR, target.getId());

        return Map.of("businessId", target.getId(), "businessName", target.getName());
    }

    public record SwitchRequest(Long businessId) {
    }
}
