package com.salonreview.sms;

import com.salonreview.config.RebookingProperties;
import com.salonreview.domain.Business;
import com.salonreview.domain.BusinessPromoConfig;
import com.salonreview.repo.BusinessPromoConfigRepository;
import com.salonreview.repo.BusinessRepository;
import com.salonreview.square.SquareClient;
import com.salonreview.square.SquareClientProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;

/**
 * Owner-configurable discount amount/minimum-spend for the {@code same_day_rebooking_discount}
 * ({@code REBOOK10}) and {@code lapsed_customer_winback}/{@code repeat_customer_winback}
 * ({@code WINBACK5}) promo links, per business — see {@link BusinessPromoConfig}.
 *
 * <p>Business A (short_code {@code akluxnails}) is the one exception: it has no
 * {@link BusinessPromoConfig} row and never will — its Square Catalog/CustomerGroup objects
 * already exist from before this service did, and their real object ids were never captured
 * anywhere in this app (only in env vars, via {@link RebookingProperties}). {@link #get} falls
 * back to those env values for Business A only; {@link #save} refuses outright for it, so this
 * tool can never create a second, conflicting set of Square objects for an account whose discount
 * already works.
 */
@Service
public class PromoConfigService {

    /** Business A's REBOOK10 terms — not owner-editable, see class doc. */
    private static final long LEGACY_REBOOK_DISCOUNT_CENTS = 1000;
    /** Business A's WINBACK5 terms — not owner-editable, see class doc. */
    private static final long LEGACY_WINBACK_DISCOUNT_CENTS = 500;
    private static final long LEGACY_WINBACK_MIN_SPEND_CENTS = 9900;

    public static final String REBOOK_PROMO_CODE = "REBOOK10";
    public static final String WINBACK_PROMO_CODE = "WINBACK5";

    private final BusinessPromoConfigRepository repository;
    private final BusinessRepository businesses;
    private final SquareClientProvider squareClientProvider;
    private final RebookingProperties legacyProperties;

    public PromoConfigService(BusinessPromoConfigRepository repository, BusinessRepository businesses,
                               SquareClientProvider squareClientProvider, RebookingProperties legacyProperties) {
        this.repository = repository;
        this.businesses = businesses;
        this.squareClientProvider = squareClientProvider;
        this.legacyProperties = legacyProperties;
    }

    public record PromoTerms(long discountCents, Long minSpendCents, String squareCustomerGroupId, boolean configured) {
    }

    /** Empty when this business hasn't set up this promo (or Business A's env config for it is
     * blank) — callers treat that as "this discount isn't available," never a default amount. */
    public Optional<PromoTerms> get(Long businessId, String promoCode) {
        Optional<BusinessPromoConfig> row = repository.findByBusinessIdAndPromoCode(businessId, promoCode);
        if (row.isPresent()) {
            BusinessPromoConfig c = row.get();
            return Optional.of(new PromoTerms(c.getDiscountCents(),
                    c.getMinSpendCents() == null ? null : c.getMinSpendCents().longValue(),
                    c.getSquareCustomerGroupId(), c.squareConfigured()));
        }
        return legacyFallback(businessId, promoCode);
    }

    private Optional<PromoTerms> legacyFallback(Long businessId, String promoCode) {
        Business legacy = businesses.legacySmsBusiness();
        if (!legacy.getId().equals(businessId)) {
            return Optional.empty();
        }
        if (REBOOK_PROMO_CODE.equals(promoCode) && legacyProperties.isAutoDiscountConfigured()) {
            return Optional.of(new PromoTerms(LEGACY_REBOOK_DISCOUNT_CENTS, null,
                    legacyProperties.getAutoDiscountGroupId(), true));
        }
        if (WINBACK_PROMO_CODE.equals(promoCode) && legacyProperties.isWinbackAutoDiscountConfigured()) {
            return Optional.of(new PromoTerms(LEGACY_WINBACK_DISCOUNT_CENTS, LEGACY_WINBACK_MIN_SPEND_CENTS,
                    legacyProperties.getWinbackAutoDiscountGroupId(), true));
        }
        return Optional.empty();
    }

    /** Creates the Square Customer Group + Discount + Pricing Rule on the first save for a
     * business/promoCode, or updates the existing ones in place on every save after that — see
     * {@link SquareClient#createDiscountAndPricingRule} / {@link SquareClient#updatePromoTerms}.
     * Refuses for Business A — see class doc. */
    @Transactional
    public PromoTerms save(Long businessId, String promoCode, long discountCents, Long minSpendCents, String updatedBy) {
        if (businesses.legacySmsBusiness().getId().equals(businessId)) {
            throw new IllegalStateException(
                    "Business A's promo terms are managed outside this tool — see PromoConfigService");
        }
        SquareClient square = squareClientProvider.forBusiness(businessId);
        Optional<BusinessPromoConfig> existing = repository.findByBusinessIdAndPromoCode(businessId, promoCode);
        BusinessPromoConfig config;
        if (existing.isPresent()) {
            config = existing.get();
            square.updatePromoTerms(config.getSquareDiscountCatalogId(), config.getSquarePricingRuleCatalogId(),
                    discountCents, minSpendCents);
        } else {
            config = BusinessPromoConfig.builder().businessId(businessId).promoCode(promoCode).build();
            String productSetId = findOrCreateProductSet(businessId, square);
            String groupId = square.createCustomerGroup(promoLabel(promoCode));
            SquareClient.PromoCatalogIds ids =
                    square.createDiscountAndPricingRule(promoLabel(promoCode), discountCents, groupId, minSpendCents, productSetId);
            config.setSquareProductSetCatalogId(productSetId);
            config.setSquareCustomerGroupId(groupId);
            config.setSquareDiscountCatalogId(ids.discountCatalogId());
            config.setSquarePricingRuleCatalogId(ids.pricingRuleCatalogId());
        }
        config.setDiscountCents((int) discountCents);
        config.setMinSpendCents(minSpendCents == null ? null : minSpendCents.intValue());
        config.setUpdatedBy(updatedBy);
        repository.save(config);
        return new PromoTerms(config.getDiscountCents(),
                config.getMinSpendCents() == null ? null : config.getMinSpendCents().longValue(),
                config.getSquareCustomerGroupId(), config.squareConfigured());
    }

    /** The two promo pricing rules for a business share one "matches every item" product set —
     * reused across REBOOK10/WINBACK5 rather than created twice. */
    private String findOrCreateProductSet(Long businessId, SquareClient square) {
        return repository.findAllByBusinessId(businessId).stream()
                .map(BusinessPromoConfig::getSquareProductSetCatalogId)
                .filter(Objects::nonNull)
                .findFirst()
                .orElseGet(() -> square.createAllProductsSet("All services (promo eligibility)"));
    }

    private static String promoLabel(String promoCode) {
        return REBOOK_PROMO_CODE.equals(promoCode) ? "Same-day rebooking discount" : "Customer winback discount";
    }

    /** {@code "$10"} for a whole-dollar amount, {@code "$12.50"} otherwise — used to interpolate
     * the owner-configured discount straight into SMS copy. */
    public static String formatDollars(long cents) {
        long dollars = cents / 100;
        long remainderCents = Math.abs(cents % 100);
        return remainderCents == 0 ? "$" + dollars : String.format("$%d.%02d", dollars, remainderCents);
    }
}
