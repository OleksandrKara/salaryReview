package com.salonreview.marketing;

import com.salonreview.config.MarketingLandingProperties;
import com.salonreview.marketing.MarketingDashboardRepository.AttributedBookingRow;
import com.salonreview.marketing.MarketingDashboardRepository.RawVariantStat;
import com.salonreview.marketing.MarketingDashboardRepository.VariantSource;
import com.salonreview.square.SquareClient;
import com.salonreview.web.dto.MarketingDashboardDto;
import com.salonreview.web.dto.MarketingDashboardDto.VariantStat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketingDashboardServiceTest {

    private MarketingDashboardRepository repository;
    private MarketingContactsService contactsService;
    private MarketingDashboardService service;

    private static final UUID LANDING_PAGE_ID = UUID.randomUUID();
    private static final UUID VARIANT_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = mock(MarketingDashboardRepository.class);
        contactsService = mock(MarketingContactsService.class);
        when(repository.findAttributedBookingRows(any(), any(), any(), any())).thenReturn(List.of());
        when(contactsService.resolveCustomerIdsByBookingId(any())).thenReturn(Map.of());
        when(contactsService.countFollowUpBookingsByVariant(any(), any(), any(), any(), any())).thenReturn(Map.of());
        MarketingLandingProperties landingProperties = new MarketingLandingProperties();
        landingProperties.setLandingBaseUrls(java.util.Map.of("mani", "https://mani.akluxnails.com"));
        SquareClient square = mock(SquareClient.class);
        when(square.locationTimeZone()).thenReturn("America/Los_Angeles");
        com.salonreview.square.SquareClientProvider squareClientProvider =
                mock(com.salonreview.square.SquareClientProvider.class);
        when(squareClientProvider.forBusiness(org.mockito.ArgumentMatchers.anyLong())).thenReturn(square);
        com.salonreview.config.CurrentBusinessContext currentBusinessContext =
                mock(com.salonreview.config.CurrentBusinessContext.class);
        when(currentBusinessContext.id()).thenReturn(1L);
        service = new MarketingDashboardService(repository, contactsService, landingProperties, squareClientProvider,
                currentBusinessContext);
    }

    private static AttributedBookingRow attributedRow(String variantId, String bookingId, String isoInstant) {
        return new AttributedBookingRow(variantId, bookingId, Instant.parse(isoInstant));
    }

    @Test
    @DisplayName("computes conversion rate from page views and distinct converted customers")
    void computesConversionRate() {
        when(repository.findLandingPageId("mani")).thenReturn(Optional.of(LANDING_PAGE_ID));
        when(repository.findVariantStats(eq(LANDING_PAGE_ID), eq("mani"), isNull(), isNull(), eq(TrafficSourceSql.ADS_ONLY))).thenReturn(List.of(
                new RawVariantStat(VARIANT_ID.toString(), "Control", 20, 100, 40, 60, "control", "Baseline, no changes")
        ));
        List<AttributedBookingRow> rows = List.of(
                attributedRow(VARIANT_ID.toString(), "booking-1", "2026-07-01T00:00:00Z"),
                attributedRow(VARIANT_ID.toString(), "booking-2", "2026-07-02T00:00:00Z"),
                attributedRow(VARIANT_ID.toString(), "booking-3", "2026-07-03T00:00:00Z"),
                attributedRow(VARIANT_ID.toString(), "booking-4", "2026-07-04T00:00:00Z")
        );
        when(repository.findAttributedBookingRows(eq(LANDING_PAGE_ID), isNull(), isNull(), eq(TrafficSourceSql.ADS_ONLY))).thenReturn(rows);
        when(contactsService.resolveCustomerIdsByBookingId("mani")).thenReturn(Map.of(
                "booking-1", "cust-1", "booking-2", "cust-2", "booking-3", "cust-3", "booking-4", "cust-4"));

        MarketingDashboardDto dashboard = service.dashboard("mani", TrafficSourceSql.ADS_ONLY, null, null);

        assertThat(dashboard.available()).isTrue();
        VariantStat variant = dashboard.variants().get(0);
        assertThat(variant.pageViews()).isEqualTo(100);
        assertThat(variant.conversions()).isEqualTo(4);
        assertThat(variant.contactsCreated()).isEqualTo(40);
        assertThat(variant.bookNowClicks()).isEqualTo(60);
        assertThat(variant.conversionRate()).isEqualTo(0.04);
        assertThat(variant.followUpBookings()).isEqualTo(0);
        assertThat(variant.adjustedConversionRate()).isEqualTo(0.04);
        assertThat(variant.deepLinkUrl()).isEqualTo("https://mani.akluxnails.com/?v=control");
        assertThat(variant.description()).isEqualTo("Baseline, no changes");
        assertThat(dashboard.statsSince()).isNull();
    }

    @Test
    @DisplayName("a returning customer's repeat tracked-flow booking on this page doesn't count as a second conversion")
    void repeatCustomerBookingDoesNotInflateConversions() {
        when(repository.findLandingPageId("mani")).thenReturn(Optional.of(LANDING_PAGE_ID));
        when(repository.findVariantStats(eq(LANDING_PAGE_ID), eq("mani"), isNull(), isNull(), eq(TrafficSourceSql.ADS_ONLY))).thenReturn(List.of(
                new RawVariantStat(VARIANT_ID.toString(), "Control", 20, 100, 0, 0, "control", null)
        ));
        // Same real customer (cust-1) booked twice through the tracked flow — only the earlier one is a genuine conversion.
        when(repository.findAttributedBookingRows(eq(LANDING_PAGE_ID), isNull(), isNull(), eq(TrafficSourceSql.ADS_ONLY))).thenReturn(List.of(
                attributedRow(VARIANT_ID.toString(), "booking-first", "2026-07-01T00:00:00Z"),
                attributedRow(VARIANT_ID.toString(), "booking-repeat", "2026-07-15T00:00:00Z")
        ));
        when(contactsService.resolveCustomerIdsByBookingId("mani")).thenReturn(Map.of(
                "booking-first", "cust-1", "booking-repeat", "cust-1"));

        MarketingDashboardDto dashboard = service.dashboard("mani", TrafficSourceSql.ADS_ONLY, null, null);

        assertThat(dashboard.variants().get(0).conversions()).isEqualTo(1);
    }

    @Test
    @DisplayName("a booking whose customer can't be resolved counts on its own rather than being silently dropped")
    void unresolvableCustomerBookingStillCountsAsConversion() {
        when(repository.findLandingPageId("mani")).thenReturn(Optional.of(LANDING_PAGE_ID));
        when(repository.findVariantStats(eq(LANDING_PAGE_ID), eq("mani"), isNull(), isNull(), eq(TrafficSourceSql.ADS_ONLY))).thenReturn(List.of(
                new RawVariantStat(VARIANT_ID.toString(), "Control", 20, 100, 0, 0, "control", null)
        ));
        when(repository.findAttributedBookingRows(eq(LANDING_PAGE_ID), isNull(), isNull(), eq(TrafficSourceSql.ADS_ONLY))).thenReturn(List.of(
                attributedRow(VARIANT_ID.toString(), "booking-unresolved", "2026-07-01T00:00:00Z")
        ));
        when(contactsService.resolveCustomerIdsByBookingId("mani")).thenReturn(Map.of()); // no resolution found

        MarketingDashboardDto dashboard = service.dashboard("mani", TrafficSourceSql.ADS_ONLY, null, null);

        assertThat(dashboard.variants().get(0).conversions()).isEqualTo(1);
    }

    @Test
    @DisplayName("adjusted conversion rate folds in manager follow-up bookings found via Square, on top of the tracked count")
    void adjustedConversionRateIncludesFollowUpBookings() {
        when(repository.findLandingPageId("mani")).thenReturn(Optional.of(LANDING_PAGE_ID));
        when(repository.findVariantStats(eq(LANDING_PAGE_ID), eq("mani"), isNull(), isNull(), eq(TrafficSourceSql.ADS_ONLY))).thenReturn(List.of(
                new RawVariantStat(VARIANT_ID.toString(), "Control", 20, 100, 40, 60, "control", null)
        ));
        List<AttributedBookingRow> rows = List.of(
                attributedRow(VARIANT_ID.toString(), "booking-1", "2026-07-01T00:00:00Z"),
                attributedRow(VARIANT_ID.toString(), "booking-2", "2026-07-02T00:00:00Z"),
                attributedRow(VARIANT_ID.toString(), "booking-3", "2026-07-03T00:00:00Z"),
                attributedRow(VARIANT_ID.toString(), "booking-4", "2026-07-04T00:00:00Z"),
                attributedRow(VARIANT_ID.toString(), "booking-5", "2026-07-05T00:00:00Z"),
                attributedRow(VARIANT_ID.toString(), "booking-6", "2026-07-06T00:00:00Z")
        );
        when(repository.findAttributedBookingRows(eq(LANDING_PAGE_ID), isNull(), isNull(), eq(TrafficSourceSql.ADS_ONLY))).thenReturn(rows);
        when(contactsService.resolveCustomerIdsByBookingId("mani")).thenReturn(Map.of(
                "booking-1", "cust-1", "booking-2", "cust-2", "booking-3", "cust-3",
                "booking-4", "cust-4", "booking-5", "cust-5", "booking-6", "cust-6"));
        when(contactsService.countFollowUpBookingsByVariant(eq("mani"), isNull(), isNull(), any(), any()))
                .thenReturn(Map.of("Control", 2L));

        MarketingDashboardDto dashboard = service.dashboard("mani", TrafficSourceSql.ADS_ONLY, null, null);

        VariantStat variant = dashboard.variants().get(0);
        assertThat(variant.conversions()).isEqualTo(6);
        assertThat(variant.followUpBookings()).isEqualTo(2);
        assertThat(variant.conversionRate()).isEqualTo(0.06);
        assertThat(variant.adjustedConversionRate()).isEqualTo(0.08);
    }

    @Test
    @DisplayName("passes the stored stats_since cutoff through to the stats query and the DTO")
    void statsSinceCutoffIsAppliedAndExposed() {
        Instant cutoff = Instant.parse("2026-07-10T09:00:00Z");
        when(repository.findLandingPageId("mani")).thenReturn(Optional.of(LANDING_PAGE_ID));
        when(repository.findStatsSince(LANDING_PAGE_ID)).thenReturn(Optional.of(cutoff));
        when(repository.findVariantStats(LANDING_PAGE_ID, "mani", cutoff, null, TrafficSourceSql.ADS_ONLY)).thenReturn(List.of());

        MarketingDashboardDto dashboard = service.dashboard("mani", TrafficSourceSql.ADS_ONLY, null, null);

        assertThat(dashboard.statsSince()).isEqualTo(cutoff.toString());
        verify(repository).findVariantStats(LANDING_PAGE_ID, "mani", cutoff, null, TrafficSourceSql.ADS_ONLY);
    }

    @Test
    @DisplayName("deep link is null when the variant has no key yet")
    void deepLinkIsNullWithoutKey() {
        when(repository.findLandingPageId("mani")).thenReturn(Optional.of(LANDING_PAGE_ID));
        when(repository.findVariantStats(eq(LANDING_PAGE_ID), eq("mani"), isNull(), isNull(), eq(TrafficSourceSql.ADS_ONLY))).thenReturn(List.of(
                new RawVariantStat(VARIANT_ID.toString(), "Control", 20, 100, 40, 60, null, null)
        ));

        MarketingDashboardDto dashboard = service.dashboard("mani", TrafficSourceSql.ADS_ONLY, null, null);

        assertThat(dashboard.variants().get(0).deepLinkUrl()).isNull();
    }

    @Test
    @DisplayName("conversion rate is zero, not a division error, when there are no page views yet")
    void zeroPageViewsYieldsZeroConversionRate() {
        when(repository.findLandingPageId("mani")).thenReturn(Optional.of(LANDING_PAGE_ID));
        when(repository.findVariantStats(eq(LANDING_PAGE_ID), eq("mani"), isNull(), isNull(), eq(TrafficSourceSql.ADS_ONLY))).thenReturn(List.of(
                new RawVariantStat(VARIANT_ID.toString(), "Control", 20, 0, 0, 0, "control", null)
        ));

        MarketingDashboardDto dashboard = service.dashboard("mani", TrafficSourceSql.ADS_ONLY, null, null);

        assertThat(dashboard.variants().get(0).conversionRate()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("returns the unavailable DTO, not a thrown exception, when the marketing schema is unreachable")
    void unavailableWhenRepositoryThrows() {
        when(repository.findLandingPageId("mani")).thenThrow(new DataAccessResourceFailureException("relation \"marketing.landing_pages\" does not exist"));

        MarketingDashboardDto dashboard = service.dashboard("mani", TrafficSourceSql.ADS_ONLY, null, null);

        assertThat(dashboard.available()).isFalse();
        assertThat(dashboard.variants()).isEmpty();
    }

    @Test
    @DisplayName("returns the unavailable DTO when the requested slug has no landing page")
    void unavailableWhenSlugNotFound() {
        when(repository.findLandingPageId("unknown-slug")).thenReturn(Optional.empty());

        MarketingDashboardDto dashboard = service.dashboard("unknown-slug", TrafficSourceSql.ADS_ONLY, null, null);

        assertThat(dashboard.available()).isFalse();
        assertThat(dashboard.landingPageSlug()).isEqualTo("unknown-slug");
    }

    @Test
    @DisplayName("rename regenerates the deep-link key from the new name")
    void renameRegeneratesKey() {
        service.renameVariant(VARIANT_ID, "Winter Gold!");

        verify(repository).renameVariant(VARIANT_ID, "Winter Gold!", "winter-gold");
    }

    @Test
    @DisplayName("rename rejects a blank name")
    void renameRejectsBlankName() {
        assertThatThrownBy(() -> service.renameVariant(VARIANT_ID, "   "))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("blank");
    }

    @Test
    @DisplayName("delete surfaces a friendly conflict instead of a raw DB error when the variant has recorded activity")
    void deleteBlockedByRecordedActivity() {
        doThrow(new DataIntegrityViolationException("fk violation")).when(repository).deleteVariant(VARIANT_ID);

        assertThatThrownBy(() -> service.deleteVariant(VARIANT_ID))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("recorded page views or bookings");
    }

    @Test
    @DisplayName("delete succeeds silently when the variant has no recorded activity")
    void deleteSucceedsWithoutActivity() {
        service.deleteVariant(VARIANT_ID);

        verify(repository).deleteVariant(VARIANT_ID);
    }

    @Test
    @DisplayName("rename surfaces a friendly conflict, not a raw 500, when the generated key collides")
    void renameBlockedByKeyCollision() {
        doThrow(new DataIntegrityViolationException("unique violation"))
                .when(repository).renameVariant(eq(VARIANT_ID), any(), any());

        assertThatThrownBy(() -> service.renameVariant(VARIANT_ID, "Control"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("already uses the link");
    }

    @Test
    @DisplayName("duplicate surfaces a friendly conflict, not a raw 500, when the generated key collides " +
            "(e.g. duplicating the same variant twice in a row with the same default name)")
    void duplicateBlockedByKeyCollision() {
        VariantSource source = new VariantSource(LANDING_PAGE_ID, 0, "{}", null);
        when(repository.findVariantSource(VARIANT_ID)).thenReturn(Optional.of(source));
        doThrow(new DataIntegrityViolationException("unique violation"))
                .when(repository).duplicateVariant(eq(source), any(), any());

        assertThatThrownBy(() -> service.duplicateVariant(VARIANT_ID, "Control (copy)"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("already uses the link");
    }

    @Test
    @DisplayName("duplicate copies weight/content from the source and auto-generates a key from the new name")
    void duplicateCopiesSourceAndGeneratesKey() {
        VariantSource source = new VariantSource(LANDING_PAGE_ID, 0, "{\"ctaText\":\"Hi\"}", "Testing a friendlier CTA");
        UUID newId = UUID.randomUUID();
        when(repository.findVariantSource(VARIANT_ID)).thenReturn(Optional.of(source));
        when(repository.duplicateVariant(source, "Holiday Gold (copy)", "holiday-gold-copy")).thenReturn(newId);

        UUID result = service.duplicateVariant(VARIANT_ID, "Holiday Gold (copy)");

        assertThat(result).isEqualTo(newId);
    }

    @Test
    @DisplayName("description is trimmed before being stored")
    void descriptionIsTrimmed() {
        service.updateVariantDescription(VARIANT_ID, "  Testing a bolder headline  ");

        verify(repository).updateVariantDescription(VARIANT_ID, "Testing a bolder headline");
    }

    @Test
    @DisplayName("a blank description clears it to null rather than storing whitespace")
    void blankDescriptionClearsToNull() {
        service.updateVariantDescription(VARIANT_ID, "   ");

        verify(repository).updateVariantDescription(VARIANT_ID, null);
    }

    @Test
    @DisplayName("duplicate 404s when the source variant doesn't exist")
    void duplicateNotFoundWhenSourceMissing() {
        when(repository.findVariantSource(VARIANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.duplicateVariant(VARIANT_ID, "Copy"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("No such variant");
    }

    @Test
    @DisplayName("updateStatsSince resolves the slug to a landing page id before writing")
    void updateStatsSinceResolvesSlug() {
        Instant cutoff = Instant.parse("2026-07-10T09:00:00Z");
        when(repository.findLandingPageId("mani")).thenReturn(Optional.of(LANDING_PAGE_ID));

        service.updateStatsSince("mani", cutoff);

        verify(repository).updateStatsSince(LANDING_PAGE_ID, cutoff);
    }
}
