package com.salonreview.square;

import com.salonreview.domain.ManualCredit;
import com.salonreview.domain.Provider;
import com.salonreview.repo.ManualCreditRepository;
import com.salonreview.repo.ProviderRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Manual service credits — owner/manager exceptions for services Square recorded too messily to
 * auto-attribute. The settlement reads them and credits the provider like a card service (see
 * SettlementPreviewService).
 */
@Service
public class ManualCreditService {

    private final ManualCreditRepository credits;
    private final ProviderRepository providers;

    public ManualCreditService(ManualCreditRepository credits, ProviderRepository providers) {
        this.credits = credits;
        this.providers = providers;
    }

    public record CreateRequest(Long providerId, LocalDate serviceDate, BigDecimal gross, BigDecimal discount,
                                BigDecimal tip, String serviceName) {}

    public record ManualCreditView(Long id, Long providerId, String providerName, String serviceDate,
                                   BigDecimal gross, BigDecimal discount, BigDecimal tip, String serviceName) {}

    public List<ManualCreditView> list() {
        Map<Long, String> names = providers.findAll().stream()
                .collect(Collectors.toMap(Provider::getId, Provider::getDisplayName, (a, b) -> a));
        return credits.findAllByOrderByServiceDateDesc().stream()
                .map(c -> new ManualCreditView(c.getId(), c.getProviderId(),
                        names.getOrDefault(c.getProviderId(), "#" + c.getProviderId()), c.getServiceDate().toString(),
                        c.getGross(), c.getDiscount(), c.getTip(), c.getServiceName()))
                .toList();
    }

    @Transactional
    public ManualCreditView create(CreateRequest req, String by) {
        if (req.providerId() == null || req.serviceDate() == null || req.gross() == null || req.gross().signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "provider, date and a positive gross are required");
        }
        if (!providers.existsById(req.providerId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "no such provider");
        }
        BigDecimal discount = req.discount() == null ? BigDecimal.ZERO : req.discount();
        BigDecimal tip = req.tip() == null ? BigDecimal.ZERO : req.tip();
        if (discount.signum() < 0 || tip.signum() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "discount and tip can't be negative");
        }
        ManualCredit saved = credits.save(ManualCredit.builder()
                .providerId(req.providerId())
                .serviceDate(req.serviceDate())
                .gross(req.gross())
                .discount(discount)
                .tip(tip)
                .serviceName(req.serviceName() == null || req.serviceName().isBlank() ? null : req.serviceName().trim())
                .createdBy(by)
                .build());
        String name = providers.findById(saved.getProviderId()).map(Provider::getDisplayName).orElse("#" + saved.getProviderId());
        return new ManualCreditView(saved.getId(), saved.getProviderId(), name, saved.getServiceDate().toString(),
                saved.getGross(), saved.getDiscount(), saved.getTip(), saved.getServiceName());
    }

    @Transactional
    public void delete(Long id) {
        if (!credits.existsById(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such credit");
        credits.deleteById(id);
    }
}
