package com.salonreview.square;

import com.salonreview.domain.AppUser;
import com.salonreview.domain.ExpenseEntry;
import com.salonreview.domain.Provider;
import com.salonreview.domain.Role;
import com.salonreview.repo.AppUserRepository;
import com.salonreview.repo.ProviderRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Recognizes a bank transaction whose description matches a known manager's or provider's
 * payee-name pattern and suggests categorizing it as the real cost of that payroll (openspec
 * design.md D11/D12): a manager match suggests {@code MANAGER_TIME}, a provider match suggests
 * {@code PROVIDER_PAYROLL}. Once a month is covered by a completed reconciliation, these linked
 * entries become the *exclusive* source for manager labor cost / provider commission on the Net
 * tab (see {@code OwnerOverviewService}), replacing the formula/clocked-hours figure for that
 * month entirely — the real disbursement is the source of truth once it's actually visible in the
 * bank data, not an addition on top of it. This is a suggestion only: it never auto-applies on its
 * own, the same human-in-the-loop guarantee as every other rule-engine match. An unrecognized
 * payee simply isn't suggested here and falls through to the normal rule-engine/Unknown path.
 */
@Component
public class PayrollDisbursementDetector {

    /** Suggestion-band confidence — always Needs Review until the owner confirms once, exactly
     * like the credit-card-payment/cash-withdrawal heuristics (design.md Edge Cases [20]). */
    private static final BigDecimal SUGGESTION_CONFIDENCE = new BigDecimal("0.60");

    private final AppUserRepository users;
    private final ProviderRepository providers;
    private final com.salonreview.config.CurrentBusinessContext currentBusinessContext;

    public PayrollDisbursementDetector(AppUserRepository users, ProviderRepository providers,
                                       com.salonreview.config.CurrentBusinessContext currentBusinessContext) {
        this.users = users;
        this.providers = providers;
        this.currentBusinessContext = currentBusinessContext;
    }

    public Optional<MerchantRuleEngine.MatchResult> suggest(String rawDescription) {
        if (rawDescription == null || rawDescription.isBlank()) return Optional.empty();
        String upper = rawDescription.toUpperCase(Locale.US);

        for (AppUser manager : users.findByBusinessIdAndRoleInAndActiveTrueOrderByUsernameAsc(
                currentBusinessContext.id(), List.of(Role.MANAGER))) {
            if (payeeNameMatches(upper, manager.getUsername())) {
                return Optional.of(suggestion(ExpenseEntry.CATEGORY_MANAGER_TIME, "manager " + manager.getUsername()));
            }
        }
        for (Provider provider : providers.findAllByActiveTrue()) {
            if (payeeNameMatches(upper, provider.getDisplayName()) || payeeNameMatches(upper, provider.getName())) {
                return Optional.of(suggestion(ExpenseEntry.CATEGORY_PROVIDER_PAYROLL, "provider " + provider.getDisplayName()));
            }
        }
        return Optional.empty();
    }

    private static MerchantRuleEngine.MatchResult suggestion(String category, String payeeDescription) {
        return new MerchantRuleEngine.MatchResult(
                category, SUGGESTION_CONFIDENCE,
                "Suggested because: description matches " + payeeDescription + "'s payout pattern",
                null, false);
    }

    /** A payee name is at least two alphabetic characters — guards against a one-letter name
     * (or a blank one) matching almost any description by accident. */
    private static boolean payeeNameMatches(String upperDescription, String payeeName) {
        if (payeeName == null) return false;
        String name = payeeName.trim().toUpperCase(Locale.US);
        return name.length() >= 2 && upperDescription.contains(name);
    }
}
