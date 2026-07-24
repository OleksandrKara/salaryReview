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

    public ManualAdjustmentService(ManualAdjustmentRepository adjustments, ProviderRepository providers) {
        this.adjustments = adjustments;
        this.providers = providers;
    }

    public record CreateRequest(Long providerId, LocalDate serviceDate, BigDecimal gross, BigDecimal discount,
                                BigDecimal tip, String serviceName) {}

    public record ManualAdjustmentView(Long id, Long providerId, String providerName, String serviceDate,
                                       BigDecimal gross, BigDecimal discount, BigDecimal tip, String serviceName) {}

    public List<ManualAdjustmentView> list() {
        Map<Long, String> names = providers.findAll().stream()
                .collect(Collectors.toMap(Provider::getId, Provider::getDisplayName, (a, b) -> a));
        return adjustments.findAllByOrderByServiceDateDesc().stream()
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
        if (!providers.existsById(req.providerId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "no such provider");
        }
        BigDecimal discount = req.discount() == null ? BigDecimal.ZERO : req.discount();
        BigDecimal tip = req.tip() == null ? BigDecimal.ZERO : req.tip();
        boolean deduction = req.gross().signum() < 0;
        if (deduction) {
            if (discount.signum() != 0 || tip.signum() != 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "a deduction (negative gross) can't carry a discount or tip");
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

    @Transactional
    public void delete(Long id) {
        if (!adjustments.existsById(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such adjustment");
        adjustments.deleteById(id);
    }

    /** Signed total of every adjustment dated within the given month — for Overview's live-month revenue. */
    public BigDecimal totalGrossForMonth(int year, int month) {
        YearMonth ym = YearMonth.of(year, month);
        return adjustments.findAllByServiceDateBetween(ym.atDay(1), ym.atEndOfMonth()).stream()
                .map(ManualAdjustment::getGross)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Signed total of every adjustment from the 1st of {@code date}'s month through {@code date}
     *  inclusive — for RevenueSnapshot's month-to-date figure. */
    public BigDecimal totalGrossThrough(LocalDate date) {
        return adjustments.findAllByServiceDateBetween(date.withDayOfMonth(1), date).stream()
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
        return adjustments.findAllByServiceDateBetween(from, to).stream()
                .filter(a -> a.getGross().abs().compareTo(cutoff) >= 0)
                .mapToInt(a -> a.getGross().signum())
                .sum();
    }
}
