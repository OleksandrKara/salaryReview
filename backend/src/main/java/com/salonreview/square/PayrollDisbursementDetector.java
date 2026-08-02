package com.salonreview.square;

import com.salonreview.domain.AppUser;
import com.salonreview.domain.BankTransaction;
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
 * payee-name pattern and suggests excluding it (openspec design.md D11). Provider payroll
 * (commission) is computed as a formula independent of the bank statement; manager labor cost is
 * computed from clocked hours ({@link ManagerTimeService}) — in both cases the real disbursement
 * must never additionally become a categorized expense on top of that already-computed figure.
 * This is a suggestion only: it never force-excludes on its own, the same human-in-the-loop
 * guarantee as every other exclude reason. An unrecognized payee simply isn't suggested here and
 * falls through to the normal rule-engine/Unknown path.
 */
@Component
public class PayrollDisbursementDetector {

    /** Suggestion-band confidence — always Needs Review until the owner confirms once, exactly
     * like the credit-card-payment/cash-withdrawal heuristics (design.md Edge Cases [20]). */
    private static final BigDecimal SUGGESTION_CONFIDENCE = new BigDecimal("0.60");

    private final AppUserRepository users;
    private final ProviderRepository providers;

    public PayrollDisbursementDetector(AppUserRepository users, ProviderRepository providers) {
        this.users = users;
        this.providers = providers;
    }

    public Optional<MerchantRuleEngine.MatchResult> suggest(String rawDescription) {
        if (rawDescription == null || rawDescription.isBlank()) return Optional.empty();
        String upper = rawDescription.toUpperCase(Locale.US);

        for (AppUser manager : users.findByRoleInAndActiveTrueOrderByUsernameAsc(List.of(Role.MANAGER))) {
            if (payeeNameMatches(upper, manager.getUsername())) {
                return Optional.of(suggestion("manager " + manager.getUsername()));
            }
        }
        for (Provider provider : providers.findAllByActiveTrue()) {
            if (payeeNameMatches(upper, provider.getDisplayName()) || payeeNameMatches(upper, provider.getName())) {
                return Optional.of(suggestion("provider " + provider.getDisplayName()));
            }
        }
        return Optional.empty();
    }

    private static MerchantRuleEngine.MatchResult suggestion(String payeeDescription) {
        return new MerchantRuleEngine.MatchResult(
                "EXCLUDE_" + BankTransaction.EXCLUDE_PAYROLL, SUGGESTION_CONFIDENCE,
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
