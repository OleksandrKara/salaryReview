package com.salonreview.web;

import com.salonreview.domain.Provider;
import com.salonreview.repo.ProviderRepository;
import com.salonreview.web.dto.ProviderCreateRequest;
import com.salonreview.web.dto.ProviderDto;
import com.salonreview.web.dto.ProviderPatchRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/providers")
public class ProviderController {

    private static final BigDecimal DEFAULT_RATE     = new BigDecimal("0.4500");
    private static final BigDecimal DEFAULT_FEE_RATE = new BigDecimal("0.0350");

    private final ProviderRepository providers;
    private final com.salonreview.config.CurrentBusinessContext currentBusinessContext;

    public ProviderController(ProviderRepository providers,
                               com.salonreview.config.CurrentBusinessContext currentBusinessContext) {
        this.providers = providers;
        this.currentBusinessContext = currentBusinessContext;
    }

    @GetMapping
    public List<ProviderDto> list(@RequestParam(name = "all", defaultValue = "false") boolean all) {
        Long businessId = currentBusinessContext.id();
        var stream = all ? providers.findAllByBusinessId(businessId).stream()
                         : providers.findAllByBusinessIdAndActiveTrue(businessId).stream();
        return stream
                .sorted(Comparator.comparing(p -> p.getDisplayName().toLowerCase()))
                .map(ProviderDto::from)
                .toList();
    }

    @PostMapping
    public ResponseEntity<ProviderDto> create(@Valid @RequestBody ProviderCreateRequest req) {
        Provider saved = providers.save(Provider.builder()
                .businessId(currentBusinessContext.id())
                .name(req.name())
                .displayName(req.displayName())
                .commissionRate(req.commissionRate() != null ? req.commissionRate() : DEFAULT_RATE)
                .cardTipFeeRate(req.cardTipFeeRate() != null ? req.cardTipFeeRate() : DEFAULT_FEE_RATE)
                .active(true)
                .build());
        return ResponseEntity.status(HttpStatus.CREATED).body(ProviderDto.from(saved));
    }

    @PatchMapping("/{id}")
    @Transactional
    public ProviderDto patch(@PathVariable Long id, @Valid @RequestBody ProviderPatchRequest req) {
        // 2026-08-18: was providers.findById(id) — business-unscoped. Any OWNER could PATCH any
        // other business's provider by id (name, commission rate, active status).
        Provider p = providers.findByIdAndBusinessId(id, currentBusinessContext.id())
                .orElseThrow(() -> new NoSuchElementException("Provider " + id + " not found"));
        if (req.name() != null)           p.setName(req.name());
        if (req.displayName() != null)    p.setDisplayName(req.displayName());
        if (req.commissionRate() != null) p.setCommissionRate(req.commissionRate());
        if (req.cardTipFeeRate() != null) p.setCardTipFeeRate(req.cardTipFeeRate());
        if (req.active() != null)         p.setActive(req.active());
        return ProviderDto.from(p);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        // 2026-08-18: was providers.existsById(id)/deleteById(id) — business-unscoped. Any OWNER
        // could DELETE any other business's provider entirely by id.
        Provider p = providers.findByIdAndBusinessId(id, currentBusinessContext.id())
                .orElseThrow(() -> new NoSuchElementException("Provider " + id + " not found"));
        providers.delete(p);     // FK ON DELETE CASCADE removes period_entries rows
        return ResponseEntity.noContent().build();
    }
}
