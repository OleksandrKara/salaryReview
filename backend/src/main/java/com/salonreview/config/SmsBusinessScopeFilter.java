package com.salonreview.config;

import com.salonreview.repo.BusinessRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Live incident fix: {@code sms_message}, {@code twilio_sms_config}, {@code telegram_config}, and
 * the automation-toggle tables have no {@code business_id} of their own yet (same gap as {@link
 * BusinessRepository#legacySmsBusiness}) — so the moment a second business's OWNER account existed,
 * that owner could read every SMS conversation, reply history, and the live Twilio/Telegram
 * credentials belonging to Business A through {@code /api/owner/automations/**},
 * {@code /api/owner/settings/sms/**}, and {@code /api/owner/settings/telegram/**}. There's no data
 * to correctly filter by business (nothing marks which business a given row belongs to), so this
 * closes the leak the same way {@code legacySmsBusiness()} keeps the SMS schedulers working: these
 * paths simply don't exist for anyone but Business A until Phase 3.7 gives this data real tenant
 * boundaries. Runs after {@link CurrentBusinessContextFilter} so the business id is already
 * resolved.
 */
public class SmsBusinessScopeFilter extends OncePerRequestFilter {

    private final CurrentBusinessContext context;
    private final BusinessRepository businesses;

    public SmsBusinessScopeFilter(CurrentBusinessContext context, BusinessRepository businesses) {
        this.context = context;
        this.businesses = businesses;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getServletPath();
        boolean scoped = path.startsWith("/api/owner/automations") || path.startsWith("/api/owner/settings/sms")
                || path.startsWith("/api/owner/settings/telegram");
        if (scoped && context.isPopulated()
                && !context.id().equals(businesses.legacySmsBusiness().getId())) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            // `code` matches GlobalExceptionHandler's BusinessSetupIncompleteException shape so the
            // frontend can render the same "here's what to do next" onboarding UX from one field,
            // regardless of whether the block came from this filter or a thrown exception.
            response.getWriter().write(
                    "{\"code\":\"sms_not_available\",\"message\":\"SMS features are not yet available for this business\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
