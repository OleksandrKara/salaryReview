package com.salonreview.config;

import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

/**
 * The authenticated caller's business for this request, resolved once by
 * {@link CurrentBusinessContextFilter} right after Spring Security authenticates and read from here
 * by repositories/services instead of threading a {@code businessId} parameter through every method
 * signature — see openspec/changes/multi-tenant-salon-platform/design.md D7. Not yet read by
 * anything: this bean and its populating filter are purely additive until the salon_config /
 * per-table business_id rewrite starts consuming it (design.md D6, tasks.md Phase 1.7+).
 */
@Component
@RequestScope
public class CurrentBusinessContext {

    private Long businessId;

    void set(Long businessId) {
        this.businessId = businessId;
    }

    /** @throws IllegalStateException if the request never resolved a business — every business-scoped
     * code path must run behind authentication, where the filter always populates this first. */
    public Long id() {
        if (businessId == null) {
            throw new IllegalStateException("CurrentBusinessContext was never populated for this request");
        }
        return businessId;
    }

    public boolean isPopulated() {
        return businessId != null;
    }
}
