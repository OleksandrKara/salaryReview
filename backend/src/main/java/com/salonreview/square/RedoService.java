package com.salonreview.square;

import com.salonreview.domain.Provider;
import com.salonreview.domain.Redo;
import com.salonreview.repo.ProviderRepository;
import com.salonreview.repo.RedoRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Redos: an unhappy customer's service redone by a different provider. Recording one moves the
 * service's commission from the original provider to the redo provider (see SettlementPreviewService
 * for how it's applied to the settlement).
 */
@Service
public class RedoService {

    private final RedoRepository redos;
    private final ProviderRepository providers;
    private final com.salonreview.config.CurrentBusinessContext currentBusinessContext;
    private final SettlementPreviewService settlementPreview;

    public RedoService(RedoRepository redos, ProviderRepository providers,
                       com.salonreview.config.CurrentBusinessContext currentBusinessContext,
                       SettlementPreviewService settlementPreview) {
        this.redos = redos;
        this.providers = providers;
        this.currentBusinessContext = currentBusinessContext;
        this.settlementPreview = settlementPreview;
    }

    public record CreateRequest(Long originalProviderId, Long redoProviderId, LocalDate originalDate,
                                LocalDate redoDate, BigDecimal amount, String serviceName) {}

    public record RedoView(Long id, Long originalProviderId, String originalProviderName, Long redoProviderId,
                           String redoProviderName, String originalDate, String redoDate, BigDecimal amount,
                           String serviceName) {}

    public List<RedoView> list() {
        Long businessId = currentBusinessContext.id();
        Map<Long, String> names = providers.findAllByBusinessId(businessId).stream()
                .collect(Collectors.toMap(Provider::getId, Provider::getDisplayName, (a, b) -> a));
        Function<Long, String> name = id -> names.getOrDefault(id, "#" + id);
        return redos.findAllByBusinessIdOrderByRedoDateDesc(businessId).stream()
                .map(r -> new RedoView(r.getId(), r.getOriginalProviderId(), name.apply(r.getOriginalProviderId()),
                        r.getRedoProviderId(), name.apply(r.getRedoProviderId()), r.getOriginalDate().toString(),
                        r.getRedoDate().toString(), r.getAmount(), r.getServiceName()))
                .toList();
    }

    @Transactional
    public RedoView create(CreateRequest req, String by) {
        if (req.originalProviderId() == null || req.redoProviderId() == null || req.originalDate() == null
                || req.redoDate() == null || req.amount() == null || req.amount().signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "original provider, redo provider, both dates and a positive amount are required");
        }
        if (req.originalProviderId().equals(req.redoProviderId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "redo provider must differ from the original");
        }
        Long businessId = currentBusinessContext.id();
        if (!providers.existsByIdAndBusinessId(req.originalProviderId(), businessId)
                || !providers.existsByIdAndBusinessId(req.redoProviderId(), businessId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "no such provider");
        }
        Redo saved = redos.save(Redo.builder()
                .originalProviderId(req.originalProviderId())
                .redoProviderId(req.redoProviderId())
                .originalDate(req.originalDate())
                .redoDate(req.redoDate())
                .amount(req.amount())
                .serviceName(req.serviceName() == null || req.serviceName().isBlank() ? null : req.serviceName().trim())
                .createdBy(by)
                .build());
        settlementPreview.invalidateCache();
        Function<Long, String> name = id -> providers.findById(id).map(Provider::getDisplayName).orElse("#" + id);
        return new RedoView(saved.getId(), saved.getOriginalProviderId(), name.apply(saved.getOriginalProviderId()),
                saved.getRedoProviderId(), name.apply(saved.getRedoProviderId()), saved.getOriginalDate().toString(),
                saved.getRedoDate().toString(), saved.getAmount(), saved.getServiceName());
    }

    /** @throws ResponseStatusException 404 if {@code id} isn't a redo of a provider belonging to
     * the current business — found live 2026-08-19 (security-review pass): a bare
     * {@code existsById}/{@code deleteById} let any business delete another's redo record, and
     * {@link #create} had the identical gap on both provider checks, letting any business move
     * commission between another business's providers entirely. */
    @Transactional
    public void delete(Long id) {
        Redo redo = redos.findByIdAndBusinessId(id, currentBusinessContext.id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such redo"));
        redos.delete(redo);
        settlementPreview.invalidateCache();
    }
}
