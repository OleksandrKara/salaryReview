package com.salonreview.square;

import com.salonreview.domain.ManualAdjustment;
import com.salonreview.domain.Provider;
import com.salonreview.repo.ManualAdjustmentRepository;
import com.salonreview.repo.ProviderRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Manual settlement adjustments — owner/manager exceptions for money Square can't reflect on its
 * own. A positive entry credits a service Square recorded too messily to auto-attribute; a
 * negative entry deducts a provider's commission for something like a refunded service. The
 * settlement reads them and folds them in like a card service (see SettlementPreviewService);
 * {@link #totalGrossForMonth} / {@link #totalGrossThrough} let the revenue-facing dashboards
 * (Overview, RevenueSnapshot) stay consistent with settlement instead of only correcting payroll.
 */
@Service
public class ManualAdjustmentService {

    private final ManualAdjustmentRepository adjustments;
    private final ProviderRepository providers;
    private final com.salonreview.config.CurrentBusinessContext currentBusinessContext;

    public ManualAdjustmentService(ManualAdjustmentRepository adjustments, ProviderRepository providers,
                                   com.salonreview.config.CurrentBusinessContext currentBusinessContext) {
        this.adjustments = adjustments;
        this.providers = providers;
        this.currentBusinessContext = currentBusinessContext;
    }

    public record CreateRequest(Long providerId, LocalDate serviceDate, BigDecimal gross, BigDecimal discount,
                                BigDecimal tip, String serviceName) {}

    public record ManualAdjustmentView(Long id, Long providerId, String providerName, String serviceDate,
                                       BigDecimal gross, BigDecimal discount, BigDecimal tip, String serviceName) {}

    public List<ManualAdjustmentView> list() {
        Long businessId = currentBusinessContext.id();
        Map<Long, String> names = providers.findAllByBusinessId(businessId).stream()
                .collect(Collectors.toMap(Provider::getId, Provider::getDisplayName, (a, b) -> a));
        return adjustments.findAllByBusinessIdOrderByServiceDateDesc(businessId).stream()
                .map(c -> new ManualAdjustmentView(c.getId(), c.getProviderId(),
                        names.getOrDefault(c.getProviderId(), "#" + c.getProviderId()), c.getServiceDate().toString(),
                        c.getGross(), c.getDiscount(), c.getTip(), c.getServiceName()))
                .toList();
    }

    @Transactional
    public ManualAdjustmentView create(CreateRequest req, String by) {
        if (req.providerId() == null || req.serviceDate() == null || req.gross() == null || req.gross().signum() == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "provider, date and a nonzero gross are required");
        }
        if (!providers.existsByIdAndBusinessId(req.providerId(), currentBusinessContext.id())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "no such provider");
        }
        BigDecimal discount = req.discount() == null ? BigDecimal.ZERO : req.discount();
        BigDecimal tip = req.tip() == null ? BigDecimal.ZERO : req.tip();
        boolean deduction = req.gross().signum() < 0;
        if (deduction) {
            // A refund typically only voids the service charge — the provider still keeps
            // whatever tip they were actually paid on that visit, so tip stays optional here
            // (entered positive; it flows into cardTips exactly like a credit's tip, so it's
            // reduced by the salon's real cardTipFeeRate automatically, same as everywhere else).
            // A discount, though, never applies to a deduction — there's no "salon-absorbed
            // discount" concept when you're clawing back a commission.
            if (discount.signum() != 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "a deduction (negative gross) can't carry a discount");
            }
            if (tip.signum() < 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tip can't be negative");
            }
            if (req.serviceName() == null || req.serviceName().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "a reason is required for a deduction");
            }
        } else if (discount.signum() < 0 || tip.signum() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "discount and tip can't be negative");
        }
        ManualAdjustment saved = adjustments.save(ManualAdjustment.builder()
                .providerId(req.providerId())
                .serviceDate(req.serviceDate())
                .gross(req.gross())
                .discount(discount)
                .tip(tip)
                .serviceName(req.serviceName() == null || req.serviceName().isBlank() ? null : req.serviceName().trim())
                .createdBy(by)
                .build());
        String name = providers.findById(saved.getProviderId()).map(Provider::getDisplayName).orElse("#" + saved.getProviderId());
        return new ManualAdjustmentView(saved.getId(), saved.getProviderId(), name, saved.getServiceDate().toString(),
                saved.getGross(), saved.getDiscount(), saved.getTip(), saved.getServiceName());
    }

    /** @throws ResponseStatusException 404 if {@code id} isn't an adjustment of a provider
     * belonging to the current business — found live 2026-08-19 (security-review pass): a bare
     * {@code existsById}/{@code deleteById} let any business delete another's manual adjustment by
     * guessing a small sequential id (and {@link #create} had the identical gap on the provider
     * check, letting any business credit/deduct commission on another business's provider). */
    @Transactional
    public void delete(Long id) {
        ManualAdjustment adjustment = adjustments.findByIdAndBusinessId(id, currentBusinessContext.id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such adjustment"));
        adjustments.delete(adjustment);
    }

    /** Signed total of every adjustment dated within the given month — for Overview's live-month revenue. */
    public BigDecimal totalGrossForMonth(int year, int month) {
        YearMonth ym = YearMonth.of(year, month);
        return adjustments.findAllByBusinessIdAndServiceDateBetween(currentBusinessContext.id(), ym.atDay(1), ym.atEndOfMonth()).stream()
                .map(ManualAdjustment::getGross)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Signed total of every adjustment from the 1st of {@code date}'s month through {@code date}
     *  inclusive — for RevenueSnapshot's month-to-date figure. */
    public BigDecimal totalGrossThrough(LocalDate date) {
        return adjustments.findAllByBusinessIdAndServiceDateBetween(currentBusinessContext.id(), date.withDayOfMonth(1), date).stream()
                .map(ManualAdjustment::getGross)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Net counted-service delta for the month (a credit adds one, a deduction like a refund
     *  removes one, when its magnitude clears {@code cutoff}) — keeps Overview's procedure count
     *  and average-ticket figure consistent with what SettlementPreviewService pays on. */
    public int countedUnitDeltaForMonth(int year, int month, BigDecimal cutoff) {
        YearMonth ym = YearMonth.of(year, month);
        return countedUnitDelta(ym.atDay(1), ym.atEndOfMonth(), cutoff);
    }

    /** Same as {@link #countedUnitDeltaForMonth}, but only through {@code date} inclusive — for
     *  RevenueSnapshot's month-to-date service count. */
    public int countedUnitDeltaThrough(LocalDate date, BigDecimal cutoff) {
        return countedUnitDelta(date.withDayOfMonth(1), date, cutoff);
    }

    private int countedUnitDelta(LocalDate from, LocalDate to, BigDecimal cutoff) {
        return adjustments.findAllByBusinessIdAndServiceDateBetween(currentBusinessContext.id(), from, to).stream()
                .filter(a -> a.getGross().abs().compareTo(cutoff) >= 0)
                .mapToInt(a -> a.getGross().signum())
                .sum();
    }
}
