package com.salonreview.square;

import com.salonreview.domain.OwnerCustomer;
import com.salonreview.repo.OwnerCustomerRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Manages the set of Square customers who are owner(s)/family. Services to them aren't charged, so the
 * aggregator credits the provider an "owner comp" at menu price (see {@link SquareMonthAggregator}).
 */
@Service
public class OwnerCustomerService {

    private final OwnerCustomerRepository repo;
    private final SquareClientProvider squareClientProvider;
    private final com.salonreview.config.CurrentBusinessContext currentBusinessContext;

    public OwnerCustomerService(OwnerCustomerRepository repo, SquareClientProvider squareClientProvider,
                                com.salonreview.config.CurrentBusinessContext currentBusinessContext) {
        this.repo = repo;
        this.squareClientProvider = squareClientProvider;
        this.currentBusinessContext = currentBusinessContext;
    }

    public record OwnerCustomerView(Long id, String squareCustomerId, String name) {}

    public record CreateRequest(String squareCustomerId, String label) {}

    public record CustomerMatch(String id, String name) {}

    /** Current owner customers, with names refreshed from Square (falls back to the stored label). */
    public List<OwnerCustomerView> list() {
        List<OwnerCustomer> all = repo.findAllByBusinessId(currentBusinessContext.id());
        Map<String, String> names;
        try {
            names = squareClientProvider.forBusiness(currentBusinessContext.id())
                    .customerNames(all.stream().map(OwnerCustomer::getSquareCustomerId).toList());
        } catch (RuntimeException e) {
            names = Map.of(); // names are a nicety; show stored labels if Square is unreachable
        }
        Map<String, String> resolved = names;
        return all.stream()
                .map(o -> new OwnerCustomerView(o.getId(), o.getSquareCustomerId(),
                        resolved.getOrDefault(o.getSquareCustomerId(), o.getLabel())))
                .toList();
    }

    public OwnerCustomerView add(CreateRequest req, String createdBy) {
        if (req == null || req.squareCustomerId() == null || req.squareCustomerId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "squareCustomerId is required");
        }
        String customerId = req.squareCustomerId().trim();
        if (repo.existsBySquareCustomerId(customerId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Customer is already marked as owner");
        }
        // Resolve the name now so the list reads well even if Square is unreachable later.
        String label = req.label();
        if (label == null || label.isBlank()) {
            label = squareClientProvider.forBusiness(currentBusinessContext.id())
                    .customerNames(List.of(customerId)).get(customerId);
        }
        OwnerCustomer saved = repo.save(OwnerCustomer.builder()
                .businessId(currentBusinessContext.id())
                .squareCustomerId(customerId)
                .label(label)
                .createdBy(createdBy)
                .build());
        return new OwnerCustomerView(saved.getId(), saved.getSquareCustomerId(), label);
    }

    public void delete(Long id) {
        repo.deleteById(id);
    }

    /** Square customers whose name matches {@code query}, for the add picker. */
    public List<CustomerMatch> search(String query) {
        return squareClientProvider.forBusiness(currentBusinessContext.id()).searchCustomers(query).stream()
                .map(c -> new CustomerMatch(c.id(), c.fullName()))
                .toList();
    }
}
