package com.salonreview.square;

import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.repo.SquareCustomerMirrorRepository;
import com.salonreview.square.SquareClient.Customer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SquareCustomerMirrorIngestServiceTest {

    private SquareClient square;
    private SquareCustomerMirrorRepository repository;
    private SquareCustomerMirrorIngestService ingest;

    @BeforeEach
    void setUp() {
        square = mock(SquareClient.class);
        SquareClientProvider provider = mock(SquareClientProvider.class);
        when(provider.forBusiness(1L)).thenReturn(square);
        CurrentBusinessContext ctx = mock(CurrentBusinessContext.class);
        when(ctx.id()).thenReturn(1L);
        repository = mock(SquareCustomerMirrorRepository.class);
        ingest = new SquareCustomerMirrorIngestService(provider, repository, ctx);
    }

    private static Customer customer(String id, String phone) {
        return new Customer(id, "Jane", "Doe", "2026-06-01T15:00:00Z", phone, "jane@example.com", null);
    }

    @Test
    @DisplayName("syncAll upserts every customer from the full-directory listing, never per-phone search")
    void syncAllUpsertsEveryCustomer() {
        when(square.listAllCustomers()).thenReturn(List.of(
                customer("CUST1", "+19165551234"), customer("CUST2", "+19165555678")));

        int count = ingest.syncAll();

        assertThat(count).isEqualTo(2);
        verify(repository).upsert(eq(1L), eq("CUST1"), eq("+19165551234"), eq("Jane"), eq("Doe"),
                eq("jane@example.com"), eq(Instant.parse("2026-06-01T15:00:00Z")));
        verify(repository).upsert(eq(1L), eq("CUST2"), eq("+19165555678"), eq("Jane"), eq("Doe"),
                eq("jane@example.com"), eq(Instant.parse("2026-06-01T15:00:00Z")));
        verify(square, times(0)).customerIdsForPhone(any());
    }

    @Test
    @DisplayName("phone is normalized the same way SquareClient#normalizePhone does before storing")
    void phoneIsNormalizedBeforeStoring() {
        when(square.listAllCustomers()).thenReturn(List.of(customer("CUST1", "(916) 555-1234")));

        ingest.syncAll();

        verify(repository).upsert(eq(1L), eq("CUST1"), eq("+19165551234"), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a customer with no id is skipped, not upserted with a null natural key")
    void customerWithNoIdIsSkipped() {
        Customer noId = new Customer(null, "Jane", "Doe", null, "+19165551234", null, null);

        ingest.upsertCustomer(1L, noId);

        verify(repository, times(0)).upsert(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("an unparseable createdAt stores null rather than throwing")
    void unparseableCreatedAtStoresNull() {
        Customer badDate = new Customer("CUST1", "Jane", "Doe", "not-a-date", "+19165551234", null, null);

        ingest.upsertCustomer(1L, badDate);

        verify(repository).upsert(eq(1L), eq("CUST1"), eq("+19165551234"), eq("Jane"), eq("Doe"),
                eq((String) null), eq((Instant) null));
    }
}
