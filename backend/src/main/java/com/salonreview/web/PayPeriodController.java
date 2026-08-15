package com.salonreview.web;

import com.salonreview.domain.Half;
import com.salonreview.domain.PayPeriod;
import com.salonreview.domain.PeriodEntry;
import com.salonreview.domain.Provider;
import com.salonreview.repo.PayPeriodRepository;
import com.salonreview.repo.PeriodEntryRepository;
import com.salonreview.repo.ProviderRepository;
import com.salonreview.web.dto.PayPeriodCreateRequest;
import com.salonreview.web.dto.PayPeriodDetailDto;
import com.salonreview.web.dto.PayPeriodDto;
import com.salonreview.web.dto.PeriodEntryDto;
import com.salonreview.web.dto.PeriodEntryUpsertRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Month;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/pay-periods")
public class PayPeriodController {

    private final PayPeriodRepository periods;
    private final PeriodEntryRepository entries;
    private final ProviderRepository providers;
    private final com.salonreview.config.CurrentBusinessContext currentBusinessContext;

    public PayPeriodController(PayPeriodRepository periods,
                               PeriodEntryRepository entries,
                               ProviderRepository providers,
                               com.salonreview.config.CurrentBusinessContext currentBusinessContext) {
        this.periods = periods;
        this.entries = entries;
        this.providers = providers;
        this.currentBusinessContext = currentBusinessContext;
    }

    @GetMapping
    public List<PayPeriodDto> list() {
        return periods.findAllByBusinessIdOrderByYearDescMonthDescHalfDesc(currentBusinessContext.id()).stream()
                .map(PayPeriodDto::from)
                .toList();
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public ResponseEntity<PayPeriodDetailDto> get(@PathVariable Long id) {
        return periods.findById(id)
                .map(period -> {
                    List<PeriodEntryDto> entryDtos = entries.findAllByPayPeriodId(id).stream()
                            .map(PeriodEntryDto::from)
                            .toList();
                    return ResponseEntity.ok(new PayPeriodDetailDto(PayPeriodDto.from(period), entryDtos));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<PayPeriodDto> create(@Valid @RequestBody PayPeriodCreateRequest req) {
        Long businessId = currentBusinessContext.id();
        periods.findByBusinessIdAndYearAndMonthAndHalf(businessId, req.year(), req.month(), req.half())
                .ifPresent(p -> { throw new IllegalArgumentException(
                        "Pay period " + p.getLabel() + " already exists"); });

        PayPeriod saved = periods.save(PayPeriod.builder()
                .businessId(businessId)
                .year(req.year())
                .month(req.month())
                .half(req.half())
                .label(labelFor(req.year(), req.month(), req.half()))
                .build());
        return ResponseEntity.status(HttpStatus.CREATED).body(PayPeriodDto.from(saved));
    }

    @PutMapping("/{periodId}/entries/{providerId}")
    @Transactional
    public PeriodEntryDto upsertEntry(@PathVariable Long periodId,
                                      @PathVariable Long providerId,
                                      @Valid @RequestBody PeriodEntryUpsertRequest req) {
        PayPeriod period = periods.findById(periodId)
                .orElseThrow(() -> new NoSuchElementException("Pay period " + periodId + " not found"));
        Provider provider = providers.findById(providerId)
                .orElseThrow(() -> new NoSuchElementException("Provider " + providerId + " not found"));

        PeriodEntry entry = entries.findByPayPeriodIdAndProviderId(periodId, providerId)
                .orElseGet(() -> PeriodEntry.builder().payPeriod(period).provider(provider).build());

        entry.setProcedures(req.procedures());
        entry.setCardTotal(req.cardTotal());
        entry.setCashTotal(req.cashTotal());
        entry.setCardTips(req.cardTips());
        entry.setAdjustmentsAmount(req.adjustmentsAmount());
        entry.setAdjustmentsNote(req.adjustmentsNote());
        entry.setCommissionRate(req.commissionRate());

        return PeriodEntryDto.from(entries.save(entry));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (!periods.existsById(id)) {
            throw new NoSuchElementException("Pay period " + id + " not found");
        }
        periods.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    static String labelFor(int year, int month, Half half) {
        String monthName = Month.of(month).getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        if (half == Half.FIRST) {
            return "1-15 " + monthName + " " + year;
        }
        int lastDay = YearMonth.of(year, month).lengthOfMonth();
        return "16-" + lastDay + " " + monthName + " " + year;
    }
}
