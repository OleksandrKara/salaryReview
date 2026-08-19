package com.salonreview;

import com.salonreview.domain.*;
import com.salonreview.repo.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2.5 — the real proof for every {@code findByBusinessId...}-style method rewritten across
 * Phases 2.1-2.4: stands up a second, synthetic business alongside Business A (backfilled by V84, so
 * always present) with one row in every scoped table, then asserts each business's scoped query
 * returns exactly its own row and never the other business's. Every prior verification pass in this
 * change only ever had ONE business in the database — this is the first place a genuine cross-tenant
 * leak could actually be observed rather than assumed. Needs a real Postgres (fails locally without
 * one, passes in CI — same as BusinessRepositoryTest).
 *
 * {@code @Transactional} so every fixture row (including business B itself) rolls back after each
 * test method — business A is the real, permanently-existing row (id constant across every run), so
 * without a rollback a second run would collide with the first run's fixed test dates/periods for
 * business A (same pattern as SmsMessageRepositoryConversationTest).
 */
@SpringBootTest
@Transactional
class CrossTenantIsolationTest {

    @Autowired private BusinessRepository businesses;
    @Autowired private ProviderRepository providers;
    @Autowired private AppUserRepository appUsers;
    @Autowired private BusinessMembershipRepository memberships;
    @Autowired private PayPeriodRepository payPeriods;
    @Autowired private RevenueSnapshotRepository revenueSnapshots;
    @Autowired private TierGrantRepository tierGrants;
    @Autowired private RedoRepository redos;
    @Autowired private ManualAdjustmentRepository manualAdjustments;
    @Autowired private SettlementFeedbackRepository settlementFeedback;
    @Autowired private ManagerTimeEntryRepository managerTimeEntries;
    @Autowired private OwnerCustomerRepository ownerCustomers;
    @Autowired private SuspiciousBookingClearanceRepository suspiciousClearances;
    @Autowired private CancellationClearanceRepository cancellationClearances;
    @Autowired private NoShowFeeOverrideRepository noShowFeeOverrides;
    @Autowired private SuspiciousTriageRepository suspiciousTriages;
    @Autowired private PrepaidPackageRepository prepaidPackages;
    @Autowired private PrepaidRedemptionRepository prepaidRedemptions;
    @Autowired private ProviderVisitRepository providerVisits;
    @Autowired private StaffDocumentRepository staffDocuments;
    @Autowired private SopRepository sops;
    @Autowired private KbArticleRepository kbArticles;
    @Autowired private KbRequestRepository kbRequests;
    @Autowired private RagDocumentRepository ragDocuments;
    @Autowired private RagChunkRepository ragChunks;
    @Autowired private RagAgentConfigRepository ragAgentConfigs;
    @Autowired private RagRedactionAuditRepository ragRedactionAudits;
    @Autowired private RagSuggestionCacheRepository ragSuggestionCache;
    @Autowired private SmsMessageRepository smsMessages;
    @Autowired private SmsReplyFlowRepository smsReplyFlows;
    @Autowired private SameDayRebookingSendRepository sameDayRebookingSends;
    @Autowired private AdSpendEntryRepository adSpendEntries;

    private Long businessAId;
    private Long businessBId;
    private Provider providerA;
    private Provider providerB;
    private AppUser userA;
    private AppUser userB;

    @BeforeEach
    void setUp() {
        businessAId = businesses.findByShortCode("akluxnails").orElseThrow().getId();
        businessBId = businesses.save(Business.builder()
                .name("Isolation Test Business B").shortCode("isolation-test-b-" + System.nanoTime())
                .timezone("UTC").active(true).build()).getId();

        providerA = providers.save(provider(businessAId, "IsoTestProviderA"));
        providerB = providers.save(provider(businessBId, "IsoTestProviderB"));

        userA = appUser(businessAId, "isotest-user-a-" + System.nanoTime());
        userB = appUser(businessBId, "isotest-user-b-" + System.nanoTime());
        userA = appUsers.save(userA);
        userB = appUsers.save(userB);
        memberships.save(BusinessMembership.builder()
                .businessId(businessAId).userId(userA.getId()).role(Role.MANAGER).build());
        memberships.save(BusinessMembership.builder()
                .businessId(businessBId).userId(userB.getId()).role(Role.MANAGER).build());
    }

    private static Provider provider(Long businessId, String name) {
        return Provider.builder().businessId(businessId).name(name).displayName(name)
                .commissionRate(new BigDecimal("0.4500")).cardTipFeeRate(new BigDecimal("0.0350"))
                .active(true).build();
    }

    private static AppUser appUser(Long businessId, String username) {
        return AppUser.builder().businessId(businessId).username(username)
                .passwordHash("unused").role(Role.MANAGER).active(true).build();
    }

    /**
     * None of these entities override equals()/hashCode() (Lombok @Builder without @Data), and a
     * repository query returns a freshly-loaded instance from a new persistence context — never the
     * same object reference returned by save(). Compare by id instead of by object identity.
     */
    private static void assertIds(List<?> rows, Long expectedPresentId, Long expectedAbsentId) {
        assertThat(rows).extracting("id").contains(expectedPresentId).doesNotContain(expectedAbsentId);
    }

    @Test
    @DisplayName("ProviderRepository: findAllByBusinessId(AndActiveTrue) never crosses businesses")
    void providerRepositoryIsolation() {
        assertIds(providers.findAllByBusinessId(businessAId), providerA.getId(), providerB.getId());
        assertIds(providers.findAllByBusinessId(businessBId), providerB.getId(), providerA.getId());
        assertIds(providers.findAllByBusinessIdAndActiveTrue(businessAId), providerA.getId(), providerB.getId());
        assertIds(providers.findAllByBusinessIdAndActiveTrue(businessBId), providerB.getId(), providerA.getId());
    }

    @Test
    @DisplayName("AppUserRepository: business-scoped listing/lookup never crosses businesses")
    void appUserRepositoryIsolation() {
        assertIds(appUsers.findAllByBusinessIdOrderByUsernameAsc(businessAId), userA.getId(), userB.getId());
        assertIds(appUsers.findAllByBusinessIdOrderByUsernameAsc(businessBId), userB.getId(), userA.getId());
        assertThat(appUsers.existsByBusinessIdAndUsername(businessAId, userA.getUsername())).isTrue();
        assertThat(appUsers.existsByBusinessIdAndUsername(businessBId, userA.getUsername())).isFalse();
        assertIds(appUsers.findByBusinessIdAndRoleInAndActiveTrueOrderByUsernameAsc(businessAId, List.of(Role.MANAGER)),
                userA.getId(), userB.getId());
        assertIds(appUsers.findByBusinessIdAndRoleInAndActiveTrueOrderByUsernameAsc(businessBId, List.of(Role.MANAGER)),
                userB.getId(), userA.getId());
    }

    @Test
    @DisplayName("PayPeriodRepository: business-scoped listing/lookup never crosses businesses")
    void payPeriodRepositoryIsolation() {
        PayPeriod periodA = payPeriods.save(PayPeriod.builder()
                .businessId(businessAId).year(2031).month(3).half(Half.FIRST).label("iso-a").build());
        PayPeriod periodB = payPeriods.save(PayPeriod.builder()
                .businessId(businessBId).year(2031).month(3).half(Half.FIRST).label("iso-b").build());

        assertIds(payPeriods.findAllByBusinessIdOrderByYearDescMonthDescHalfDesc(businessAId),
                periodA.getId(), periodB.getId());
        assertIds(payPeriods.findAllByBusinessIdOrderByYearDescMonthDescHalfDesc(businessBId),
                periodB.getId(), periodA.getId());
        assertThat(payPeriods.findByBusinessIdAndYearAndMonthAndHalf(businessAId, 2031, 3, Half.FIRST))
                .map(PayPeriod::getId).contains(periodA.getId());
        assertThat(payPeriods.findByBusinessIdAndYearAndMonthAndHalf(businessBId, 2031, 3, Half.FIRST))
                .map(PayPeriod::getId).contains(periodB.getId());
        assertIds(payPeriods.findAllByBusinessIdAndYearOrderByMonthAscHalfAsc(businessAId, 2031),
                periodA.getId(), periodB.getId());
        assertIds(payPeriods.findAllByBusinessIdAndYearOrderByMonthAscHalfAsc(businessBId, 2031),
                periodB.getId(), periodA.getId());
    }

    @Test
    @DisplayName("RevenueSnapshotRepository: business-scoped lookup never crosses businesses, even for the SAME calendar date")
    void revenueSnapshotRepositoryIsolation() {
        // Same date on purpose — this is exactly the scenario the composite unique constraint exists
        // for (see the PR that scoped this repository): both businesses' daily jobs land on the same
        // calendar date without colliding.
        LocalDate date = LocalDate.of(2031, 3, 15);
        RevenueSnapshot snapA = revenueSnapshots.save(snapshot(businessAId, date, "100.00"));
        RevenueSnapshot snapB = revenueSnapshots.save(snapshot(businessBId, date, "999.00"));

        assertThat(revenueSnapshots.findByBusinessIdAndSnapshotDate(businessAId, date))
                .map(RevenueSnapshot::getId).contains(snapA.getId());
        assertThat(revenueSnapshots.findByBusinessIdAndSnapshotDate(businessAId, date).orElseThrow().getMtdRevenue())
                .isEqualByComparingTo("100.00");
        assertThat(revenueSnapshots.findByBusinessIdAndSnapshotDate(businessBId, date))
                .map(RevenueSnapshot::getId).contains(snapB.getId());
        assertThat(revenueSnapshots.findByBusinessIdAndSnapshotDate(businessBId, date).orElseThrow().getMtdRevenue())
                .isEqualByComparingTo("999.00");

        snapA.setMonthEndActual(new BigDecimal("100.00"));
        snapB.setMonthEndActual(new BigDecimal("999.00"));
        revenueSnapshots.saveAll(List.of(snapA, snapB));
        assertIds(revenueSnapshots.findAllByBusinessIdAndMonthEndActualIsNotNullOrderBySnapshotDateDesc(
                businessAId, PageRequest.of(0, 10)), snapA.getId(), snapB.getId());
        assertIds(revenueSnapshots.findAllByBusinessIdAndMonthEndActualIsNotNullOrderBySnapshotDateDesc(
                businessBId, PageRequest.of(0, 10)), snapB.getId(), snapA.getId());

        assertIds(revenueSnapshots.findAllByBusinessIdAndSnapshotDateBetween(
                businessAId, date.minusDays(1), date.plusDays(1)), snapA.getId(), snapB.getId());
        assertIds(revenueSnapshots.findAllByBusinessIdAndSnapshotDateBetween(
                businessBId, date.minusDays(1), date.plusDays(1)), snapB.getId(), snapA.getId());
    }

    private static RevenueSnapshot snapshot(Long businessId, LocalDate date, String mtd) {
        return RevenueSnapshot.builder().businessId(businessId).snapshotDate(date)
                .mtdRevenue(new BigDecimal(mtd)).mtdCard(new BigDecimal(mtd)).mtdCash(BigDecimal.ZERO)
                .mtdServices(1).upcomingCount(0).upcomingGross(BigDecimal.ZERO)
                .createdAt(Instant.now()).build();
    }

    @Test
    @DisplayName("TierGrantRepository: the provider-join scoping never crosses businesses")
    void tierGrantRepositoryIsolation() {
        TierGrant grantA = tierGrants.save(TierGrant.builder()
                .providerId(providerA.getId()).year(2031).month(4).build());
        TierGrant grantB = tierGrants.save(TierGrant.builder()
                .providerId(providerB.getId()).year(2031).month(4).build());

        assertIds(tierGrants.findByBusinessIdAndYearAndMonth(businessAId, 2031, 4), grantA.getId(), grantB.getId());
        assertIds(tierGrants.findByBusinessIdAndYearAndMonth(businessBId, 2031, 4), grantB.getId(), grantA.getId());
    }

    @Test
    @DisplayName("RedoRepository: the provider-join scoping never crosses businesses")
    void redoRepositoryIsolation() {
        Redo redoA = redos.save(Redo.builder()
                .originalProviderId(providerA.getId()).redoProviderId(providerA.getId())
                .originalDate(LocalDate.of(2031, 4, 1)).redoDate(LocalDate.of(2031, 4, 2))
                .amount(new BigDecimal("50.00")).build());
        Redo redoB = redos.save(Redo.builder()
                .originalProviderId(providerB.getId()).redoProviderId(providerB.getId())
                .originalDate(LocalDate.of(2031, 4, 1)).redoDate(LocalDate.of(2031, 4, 2))
                .amount(new BigDecimal("50.00")).build());

        assertIds(redos.findAllByBusinessIdOrderByRedoDateDesc(businessAId), redoA.getId(), redoB.getId());
        assertIds(redos.findAllByBusinessIdOrderByRedoDateDesc(businessBId), redoB.getId(), redoA.getId());
    }

    @Test
    @DisplayName("ManualAdjustmentRepository: the provider-join scoping never crosses businesses")
    void manualAdjustmentRepositoryIsolation() {
        LocalDate date = LocalDate.of(2031, 4, 5);
        ManualAdjustment adjA = manualAdjustments.save(ManualAdjustment.builder()
                .providerId(providerA.getId()).serviceDate(date).gross(new BigDecimal("30.00"))
                .discount(BigDecimal.ZERO).tip(BigDecimal.ZERO).build());
        ManualAdjustment adjB = manualAdjustments.save(ManualAdjustment.builder()
                .providerId(providerB.getId()).serviceDate(date).gross(new BigDecimal("30.00"))
                .discount(BigDecimal.ZERO).tip(BigDecimal.ZERO).build());

        assertIds(manualAdjustments.findAllByBusinessIdOrderByServiceDateDesc(businessAId), adjA.getId(), adjB.getId());
        assertIds(manualAdjustments.findAllByBusinessIdOrderByServiceDateDesc(businessBId), adjB.getId(), adjA.getId());
        assertIds(manualAdjustments.findAllByBusinessIdAndServiceDateBetween(
                businessAId, date.minusDays(1), date.plusDays(1)), adjA.getId(), adjB.getId());
        assertIds(manualAdjustments.findAllByBusinessIdAndServiceDateBetween(
                businessBId, date.minusDays(1), date.plusDays(1)), adjB.getId(), adjA.getId());
    }

    @Test
    @DisplayName("SettlementFeedbackRepository: the provider-join scoping never crosses businesses")
    void settlementFeedbackRepositoryIsolation() {
        SettlementFeedback fbA = settlementFeedback.save(SettlementFeedback.builder()
                .providerId(providerA.getId()).year(2031).month(5).half(Half.FIRST)
                .status(FeedbackStatus.APPROVED).updatedAt(Instant.now()).build());
        SettlementFeedback fbB = settlementFeedback.save(SettlementFeedback.builder()
                .providerId(providerB.getId()).year(2031).month(5).half(Half.FIRST)
                .status(FeedbackStatus.APPROVED).updatedAt(Instant.now()).build());

        assertIds(settlementFeedback.findByBusinessIdAndYearAndMonth(businessAId, 2031, 5), fbA.getId(), fbB.getId());
        assertIds(settlementFeedback.findByBusinessIdAndYearAndMonth(businessBId, 2031, 5), fbB.getId(), fbA.getId());
    }

    @Test
    @DisplayName("ManagerTimeEntryRepository: the app_user-join scoping never crosses businesses")
    void managerTimeEntryRepositoryIsolation() {
        LocalDate work = LocalDate.of(2031, 4, 10);
        ManagerTimeEntry entryA = managerTimeEntries.save(ManagerTimeEntry.builder()
                .userId(userA.getId()).workDate(work).startAt(Instant.parse("2031-04-10T16:00:00Z")).build());
        ManagerTimeEntry entryB = managerTimeEntries.save(ManagerTimeEntry.builder()
                .userId(userB.getId()).workDate(work).startAt(Instant.parse("2031-04-10T16:00:00Z")).build());

        assertIds(managerTimeEntries.findByBusinessIdAndWorkDateBetween(businessAId, work.minusDays(1), work.plusDays(1)),
                entryA.getId(), entryB.getId());
        assertIds(managerTimeEntries.findByBusinessIdAndWorkDateBetween(businessBId, work.minusDays(1), work.plusDays(1)),
                entryB.getId(), entryA.getId());
        // Both shifts are still open (no endAt) — the "clocked in now" query must isolate too.
        assertIds(managerTimeEntries.findByBusinessIdAndEndAtIsNull(businessAId), entryA.getId(), entryB.getId());
        assertIds(managerTimeEntries.findByBusinessIdAndEndAtIsNull(businessBId), entryB.getId(), entryA.getId());
    }

    @Test
    @DisplayName("OwnerCustomerRepository: findAllByBusinessId never crosses businesses")
    void ownerCustomerRepositoryIsolation() {
        OwnerCustomer ownerA = ownerCustomers.save(OwnerCustomer.builder()
                .businessId(businessAId).squareCustomerId("ISO_CUST_A_" + System.nanoTime()).build());
        OwnerCustomer ownerB = ownerCustomers.save(OwnerCustomer.builder()
                .businessId(businessBId).squareCustomerId("ISO_CUST_B_" + System.nanoTime()).build());

        assertIds(ownerCustomers.findAllByBusinessId(businessAId), ownerA.getId(), ownerB.getId());
        assertIds(ownerCustomers.findAllByBusinessId(businessBId), ownerB.getId(), ownerA.getId());
    }

    /**
     * 2026-08-18 cross-tenant fix: {@code owner_customer}'s unique constraint used to be a
     * single-column {@code UNIQUE (square_customer_id)}, and {@code OwnerCustomerService#delete}
     * was a bare {@code repo.deleteById(id)} — any business could delete another business's row by
     * guessing a small sequential id, and no two businesses could ever mark the SAME Square
     * customer id as an owner. This test proves both halves of the fix against a real Postgres:
     * the widened composite constraint lets both businesses use the same squareCustomerId, and
     * {@code findByIdAndBusinessId} correctly refuses to see the other business's row by id.
     */
    @Test
    @DisplayName("2026-08-18: OwnerCustomerRepository — findByIdAndBusinessId is scoped, and the "
            + "widened (business_id, square_customer_id) constraint lets both businesses share a "
            + "squareCustomerId without colliding")
    void ownerCustomerRepositoryCrossTenantFix() {
        String sharedCustomerId = "ISO_SHARED_CUST_" + System.nanoTime();
        OwnerCustomer ownerA = ownerCustomers.save(OwnerCustomer.builder()
                .businessId(businessAId).squareCustomerId(sharedCustomerId).build());
        // Same Square customer id, different business — would have violated the old single-column
        // unique constraint; now succeeds under the widened composite one.
        OwnerCustomer ownerB = ownerCustomers.save(OwnerCustomer.builder()
                .businessId(businessBId).squareCustomerId(sharedCustomerId).build());

        assertThat(ownerCustomers.findByIdAndBusinessId(ownerA.getId(), businessAId)).isPresent();
        assertThat(ownerCustomers.findByIdAndBusinessId(ownerA.getId(), businessBId)).isEmpty();
        assertThat(ownerCustomers.findByIdAndBusinessId(ownerB.getId(), businessBId)).isPresent();
        assertThat(ownerCustomers.findByIdAndBusinessId(ownerB.getId(), businessAId)).isEmpty();

        assertThat(ownerCustomers.existsByBusinessIdAndSquareCustomerId(businessAId, sharedCustomerId)).isTrue();
        assertThat(ownerCustomers.existsByBusinessIdAndSquareCustomerId(businessBId, sharedCustomerId)).isTrue();
    }

    /**
     * 2026-08-18 cross-tenant fix: same shape as owner_customer above, for
     * {@code suspicious_booking_clearance}. {@code SuspiciousBookingService#clear/unclear} used to
     * resolve/delete by bare {@code square_booking_id}; now scoped by business, and the widened
     * constraint lets both businesses clear a booking under the same Square id independently.
     */
    @Test
    @DisplayName("2026-08-18: SuspiciousBookingClearanceRepository — business-scoped lookup/delete, "
            + "widened constraint allows the same squareBookingId across businesses")
    void suspiciousBookingClearanceCrossTenantFix() {
        String sharedBookingId = "ISO_SHARED_BK_" + System.nanoTime();
        SuspiciousBookingClearance clearanceA = suspiciousClearances.save(SuspiciousBookingClearance.builder()
                .businessId(businessAId).squareBookingId(sharedBookingId)
                .clearedByUsername("owner-a").clearedAt(Instant.now()).build());
        SuspiciousBookingClearance clearanceB = suspiciousClearances.save(SuspiciousBookingClearance.builder()
                .businessId(businessBId).squareBookingId(sharedBookingId)
                .clearedByUsername("owner-b").clearedAt(Instant.now()).build());

        assertThat(suspiciousClearances.findByBusinessIdAndSquareBookingId(businessAId, sharedBookingId))
                .isPresent().get().extracting(SuspiciousBookingClearance::getId).isEqualTo(clearanceA.getId());
        assertThat(suspiciousClearances.findByBusinessIdAndSquareBookingId(businessBId, sharedBookingId))
                .isPresent().get().extracting(SuspiciousBookingClearance::getId).isEqualTo(clearanceB.getId());

        suspiciousClearances.deleteByBusinessIdAndSquareBookingId(businessAId, sharedBookingId);
        // Business A's row is gone; business B's row (same Square booking id) is untouched.
        assertThat(suspiciousClearances.findByBusinessIdAndSquareBookingId(businessAId, sharedBookingId)).isEmpty();
        assertThat(suspiciousClearances.findByBusinessIdAndSquareBookingId(businessBId, sharedBookingId)).isPresent();
    }

    /** 2026-08-18 cross-tenant fix: same shape, for {@code cancellation_clearance}. */
    @Test
    @DisplayName("2026-08-18: CancellationClearanceRepository — business-scoped lookup/delete, "
            + "widened constraint allows the same squareBookingId across businesses")
    void cancellationClearanceCrossTenantFix() {
        String sharedBookingId = "ISO_SHARED_CANCEL_BK_" + System.nanoTime();
        CancellationClearance clearanceA = cancellationClearances.save(CancellationClearance.builder()
                .businessId(businessAId).squareBookingId(sharedBookingId)
                .clearedByUsername("owner-a").clearedAt(Instant.now()).build());
        CancellationClearance clearanceB = cancellationClearances.save(CancellationClearance.builder()
                .businessId(businessBId).squareBookingId(sharedBookingId)
                .clearedByUsername("owner-b").clearedAt(Instant.now()).build());

        assertThat(cancellationClearances.findByBusinessIdAndSquareBookingId(businessAId, sharedBookingId))
                .isPresent().get().extracting(CancellationClearance::getId).isEqualTo(clearanceA.getId());
        assertThat(cancellationClearances.findByBusinessIdAndSquareBookingId(businessBId, sharedBookingId))
                .isPresent().get().extracting(CancellationClearance::getId).isEqualTo(clearanceB.getId());

        cancellationClearances.deleteByBusinessIdAndSquareBookingId(businessAId, sharedBookingId);
        assertThat(cancellationClearances.findByBusinessIdAndSquareBookingId(businessAId, sharedBookingId)).isEmpty();
        assertThat(cancellationClearances.findByBusinessIdAndSquareBookingId(businessBId, sharedBookingId)).isPresent();
    }

    /**
     * 2026-08-18 cross-tenant fix: same shape, for {@code no_show_fee_override}. Previously
     * {@code NoShowFeeService#confirm/suppress} could silently take another business's override
     * row over (lookup-then-reassign businessId); {@code clearOverride} could delete it outright.
     */
    @Test
    @DisplayName("2026-08-18: NoShowFeeOverrideRepository — business-scoped lookup/delete, widened "
            + "constraint allows the same squareBookingId across businesses")
    void noShowFeeOverrideCrossTenantFix() {
        String sharedBookingId = "ISO_SHARED_NOSHOW_BK_" + System.nanoTime();
        NoShowFeeOverride overrideA = noShowFeeOverrides.save(NoShowFeeOverride.builder()
                .businessId(businessAId).squareBookingId(sharedBookingId)
                .kind(NoShowFeeOverride.SUPPRESS).amount(BigDecimal.ZERO).build());
        NoShowFeeOverride overrideB = noShowFeeOverrides.save(NoShowFeeOverride.builder()
                .businessId(businessBId).squareBookingId(sharedBookingId)
                .kind(NoShowFeeOverride.SUPPRESS).amount(BigDecimal.ZERO).build());

        assertThat(noShowFeeOverrides.findByBusinessIdAndSquareBookingId(businessAId, sharedBookingId))
                .isPresent().get().extracting(NoShowFeeOverride::getId).isEqualTo(overrideA.getId());
        assertThat(noShowFeeOverrides.findByBusinessIdAndSquareBookingId(businessBId, sharedBookingId))
                .isPresent().get().extracting(NoShowFeeOverride::getId).isEqualTo(overrideB.getId());

        noShowFeeOverrides.deleteByBusinessIdAndSquareBookingId(businessAId, sharedBookingId);
        assertThat(noShowFeeOverrides.findByBusinessIdAndSquareBookingId(businessAId, sharedBookingId)).isEmpty();
        assertThat(noShowFeeOverrides.findByBusinessIdAndSquareBookingId(businessBId, sharedBookingId)).isPresent();
    }

    /**
     * 2026-08-18 cross-tenant fix: {@code suspicious_triage}'s cache lookup used to be keyed by
     * bare {@code (square_booking_id, prompt_version)} — a cache hit for one business's booking id
     * would leak that business's AI-generated explanation/draft message to another business on a
     * collision. Proves the widened 3-column constraint lets both businesses cache a triage under
     * the same Square booking id, and that each business only ever sees its own.
     */
    @Test
    @DisplayName("2026-08-18: SuspiciousTriageRepository — cache lookup is scoped to business, "
            + "widened constraint allows the same squareBookingId across businesses")
    void suspiciousTriageCrossTenantFix() {
        String sharedBookingId = "ISO_SHARED_TRIAGE_BK_" + System.nanoTime();
        SuspiciousTriage triageA = suspiciousTriages.save(triage(businessAId, sharedBookingId, "business A's private explanation"));
        SuspiciousTriage triageB = suspiciousTriages.save(triage(businessBId, sharedBookingId, "business B's private explanation"));

        assertThat(suspiciousTriages.findByBusinessIdAndSquareBookingIdAndPromptVersion(
                        businessAId, sharedBookingId, "v-iso-test"))
                .isPresent().get().extracting(SuspiciousTriage::getExplanation)
                .isEqualTo("business A's private explanation");
        assertThat(suspiciousTriages.findByBusinessIdAndSquareBookingIdAndPromptVersion(
                        businessBId, sharedBookingId, "v-iso-test"))
                .isPresent().get().extracting(SuspiciousTriage::getExplanation)
                .isEqualTo("business B's private explanation");
        assertThat(triageA.getId()).isNotEqualTo(triageB.getId());
    }

    private static SuspiciousTriage triage(Long businessId, String bookingId, String explanation) {
        return SuspiciousTriage.builder().businessId(businessId).squareBookingId(bookingId)
                .promptVersion("v-iso-test").classification(TriageClassification.NEEDS_REVIEW)
                .confidence(new BigDecimal("0.500")).explanation(explanation).draftMessage("")
                .signals(List.of()).model("claude-haiku-4-5").build();
    }

    @Test
    @DisplayName("PrepaidPackageRepository / PrepaidRedemptionRepository never cross businesses")
    void prepaidRepositoriesIsolation() {
        LocalDate serviceDate = LocalDate.of(2031, 4, 20);
        PrepaidPackage pkgA = prepaidPackages.save(PrepaidPackage.builder()
                .businessId(businessAId).customerName("Iso Test A").paidDate(LocalDate.of(2031, 4, 1))
                .amount(new BigDecimal("200.00")).totalServices(2).build());
        PrepaidPackage pkgB = prepaidPackages.save(PrepaidPackage.builder()
                .businessId(businessBId).customerName("Iso Test B").paidDate(LocalDate.of(2031, 4, 1))
                .amount(new BigDecimal("200.00")).totalServices(2).build());

        assertIds(prepaidPackages.findAllByBusinessIdOrderByPaidDateDesc(businessAId), pkgA.getId(), pkgB.getId());
        assertIds(prepaidPackages.findAllByBusinessIdOrderByPaidDateDesc(businessBId), pkgB.getId(), pkgA.getId());

        PrepaidRedemption redA = prepaidRedemptions.save(PrepaidRedemption.builder()
                .packageId(pkgA.getId()).providerId(providerA.getId())
                .squareBookingId("ISO_BK_A_" + System.nanoTime()).serviceVariationId("VAR_A")
                .serviceDate(serviceDate).menuPrice(new BigDecimal("100.00")).counts(true).build());
        PrepaidRedemption redB = prepaidRedemptions.save(PrepaidRedemption.builder()
                .packageId(pkgB.getId()).providerId(providerB.getId())
                .squareBookingId("ISO_BK_B_" + System.nanoTime()).serviceVariationId("VAR_B")
                .serviceDate(serviceDate).menuPrice(new BigDecimal("100.00")).counts(true).build());

        assertIds(prepaidRedemptions.findByBusinessIdAndServiceDateBetween(
                businessAId, serviceDate.minusDays(1), serviceDate.plusDays(1)), redA.getId(), redB.getId());
        assertIds(prepaidRedemptions.findByBusinessIdAndServiceDateBetween(
                businessBId, serviceDate.minusDays(1), serviceDate.plusDays(1)), redB.getId(), redA.getId());

        // 2026-08-18 cross-tenant fix: PrepaidService#delete/redeem/candidates/undoRedemption all
        // used to resolve by bare id (packages.findById/existsById, redemptions.existsById) with no
        // business check — any business could delete or redeem against another business's package,
        // or delete another business's redemption, by guessing a small sequential id. Proves the
        // fix's id-scoped lookups against a real Postgres.
        assertThat(prepaidPackages.findByIdAndBusinessId(pkgA.getId(), businessAId)).isPresent();
        assertThat(prepaidPackages.findByIdAndBusinessId(pkgA.getId(), businessBId)).isEmpty();
        assertThat(prepaidPackages.findByIdAndBusinessId(pkgB.getId(), businessBId)).isPresent();
        assertThat(prepaidPackages.findByIdAndBusinessId(pkgB.getId(), businessAId)).isEmpty();

        assertThat(prepaidRedemptions.findByIdAndBusinessId(redA.getId(), businessAId)).isPresent();
        assertThat(prepaidRedemptions.findByIdAndBusinessId(redA.getId(), businessBId)).isEmpty();
        assertThat(prepaidRedemptions.findByIdAndBusinessId(redB.getId(), businessBId)).isPresent();
        assertThat(prepaidRedemptions.findByIdAndBusinessId(redB.getId(), businessAId)).isEmpty();
    }

    @Test
    @DisplayName("ProviderVisitRepository: findAllByBusinessId... never crosses businesses")
    void providerVisitRepositoryIsolation() {
        LocalDate visitDate = LocalDate.of(2031, 4, 25);
        ProviderVisit visitA = providerVisits.save(ProviderVisit.builder()
                .businessId(businessAId).customerId("ISO_VCUST_A_" + System.nanoTime())
                .providerRef("ISO_PREF_A").serviceDate(visitDate).createdAt(Instant.now()).build());
        ProviderVisit visitB = providerVisits.save(ProviderVisit.builder()
                .businessId(businessBId).customerId("ISO_VCUST_B_" + System.nanoTime())
                .providerRef("ISO_PREF_B").serviceDate(visitDate).createdAt(Instant.now()).build());

        assertIds(providerVisits.findAllByBusinessIdOrderByServiceDateAsc(businessAId), visitA.getId(), visitB.getId());
        assertIds(providerVisits.findAllByBusinessIdOrderByServiceDateAsc(businessBId), visitB.getId(), visitA.getId());
        assertIds(providerVisits.findByBusinessIdAndServiceDateBetween(
                businessAId, visitDate.minusDays(1), visitDate.plusDays(1)), visitA.getId(), visitB.getId());
        assertIds(providerVisits.findByBusinessIdAndServiceDateBetween(
                businessBId, visitDate.minusDays(1), visitDate.plusDays(1)), visitB.getId(), visitA.getId());
        assertThat(providerVisits.countByBusinessIdAndServiceDateBetween(
                businessAId, visitDate.minusDays(1), visitDate.plusDays(1))).isEqualTo(1);
        assertThat(providerVisits.countByBusinessIdAndServiceDateBetween(
                businessBId, visitDate.minusDays(1), visitDate.plusDays(1))).isEqualTo(1);
    }

    @Test
    @DisplayName("StaffDocumentRepository: the provider/app_user-join scoping never crosses businesses, for either kind of document")
    void staffDocumentRepositoryIsolation() {
        StaffDocument providerDocA = staffDocuments.save(StaffDocument.builder()
                .providerId(providerA.getId()).documentType("License").fileName("a.pdf")
                .contentType("application/pdf").fileData(new byte[]{1}).expirationDate(LocalDate.of(2031, 6, 1))
                .createdBy("owner").build());
        StaffDocument providerDocB = staffDocuments.save(StaffDocument.builder()
                .providerId(providerB.getId()).documentType("License").fileName("b.pdf")
                .contentType("application/pdf").fileData(new byte[]{1}).expirationDate(LocalDate.of(2031, 6, 2))
                .createdBy("owner").build());
        StaffDocument managerDocA = staffDocuments.save(StaffDocument.builder()
                .appUserId(userA.getId()).documentType("NDA").fileName("nda-a.pdf")
                .contentType("application/pdf").fileData(new byte[]{1}).expirationDate(LocalDate.of(2031, 6, 3))
                .createdBy("owner").build());

        assertIds(staffDocuments.findAllByBusinessIdOrderByExpirationDateAsc(businessAId),
                providerDocA.getId(), providerDocB.getId());
        assertThat(staffDocuments.findAllByBusinessIdOrderByExpirationDateAsc(businessAId))
                .extracting("id").contains(managerDocA.getId());
        assertIds(staffDocuments.findAllByBusinessIdOrderByExpirationDateAsc(businessBId),
                providerDocB.getId(), providerDocA.getId());

        assertThat(staffDocuments.findByIdAndBusinessId(providerDocA.getId(), businessAId)).isPresent();
        assertThat(staffDocuments.findByIdAndBusinessId(providerDocA.getId(), businessBId)).isEmpty();
        assertThat(staffDocuments.findByIdAndBusinessId(managerDocA.getId(), businessAId)).isPresent();
        assertThat(staffDocuments.findByIdAndBusinessId(managerDocA.getId(), businessBId)).isEmpty();
    }

    @Test
    @DisplayName("SopRepository: business_id scoping never crosses businesses, for the list and single-lookup methods")
    void sopRepositoryIsolation() {
        Sop sopA = sops.save(Sop.builder().businessId(businessAId).title("Cleaning A").category("Hygiene")
                .audience(SopAudience.BOTH).status(SopStatus.ACTIVE).createdBy("owner").build());
        Sop sopB = sops.save(Sop.builder().businessId(businessBId).title("Cleaning B").category("Hygiene")
                .audience(SopAudience.BOTH).status(SopStatus.ACTIVE).createdBy("owner").build());
        Sop draftA = sops.save(Sop.builder().businessId(businessAId).title("Draft A").category("Hygiene")
                .audience(SopAudience.BOTH).status(SopStatus.ARCHIVED).createdBy("owner").build());

        assertIds(sops.findAllByBusinessIdOrderByPriorityAscCategoryAscTitleAsc(businessAId), sopA.getId(), sopB.getId());
        assertThat(sops.findAllByBusinessIdOrderByPriorityAscCategoryAscTitleAsc(businessAId))
                .extracting("id").contains(draftA.getId());
        assertIds(sops.findAllByBusinessIdOrderByPriorityAscCategoryAscTitleAsc(businessBId), sopB.getId(), sopA.getId());

        assertIds(sops.findByBusinessIdAndStatusOrderByPriorityAscCategoryAscTitleAsc(businessAId, SopStatus.ACTIVE),
                sopA.getId(), sopB.getId());
        assertThat(sops.findByBusinessIdAndStatusOrderByPriorityAscCategoryAscTitleAsc(businessAId, SopStatus.ACTIVE))
                .extracting("id").doesNotContain(draftA.getId());

        assertThat(sops.findByIdAndBusinessId(sopA.getId(), businessAId)).isPresent();
        assertThat(sops.findByIdAndBusinessId(sopA.getId(), businessBId)).isEmpty();
        assertThat(sops.findByIdAndBusinessId(sopB.getId(), businessBId)).isPresent();
        assertThat(sops.findByIdAndBusinessId(sopB.getId(), businessAId)).isEmpty();
    }

    @Test
    @DisplayName("KbArticleRepository: business_id scoping never crosses businesses, for the list, pending-sync, and single-lookup methods")
    void kbArticleRepositoryIsolation() {
        KbArticle articleA = kbArticles.save(KbArticle.builder().businessId(businessAId).title("Refunds A")
                .category("FAQ").body("x").contentHash("hash-a").syncStatus(SyncStatus.NOT_SYNCED)
                .createdBy("owner").build());
        KbArticle articleB = kbArticles.save(KbArticle.builder().businessId(businessBId).title("Refunds B")
                .category("FAQ").body("x").contentHash("hash-b").syncStatus(SyncStatus.NOT_SYNCED)
                .createdBy("owner").build());
        RagDocument ragDoc = ragDocuments.save(RagDocument.builder().businessId(businessAId).filename("f.txt")
                .sourceType("TEXT").extractedText("x").status(RagDocumentStatus.INDEXED).uploadedBy("owner")
                .createdAt(Instant.now()).build());
        KbArticle syncedA = kbArticles.save(KbArticle.builder().businessId(businessAId).title("Synced A")
                .category("FAQ").body("x").contentHash("hash-c").ragDocId(ragDoc.getId()).syncStatus(SyncStatus.SYNCED)
                .createdBy("owner").build());

        assertIds(kbArticles.findAllByBusinessIdOrderByCategoryAscTitleAsc(businessAId), articleA.getId(), articleB.getId());
        assertIds(kbArticles.findAllByBusinessIdOrderByCategoryAscTitleAsc(businessBId), articleB.getId(), articleA.getId());

        assertIds(kbArticles.findPendingSyncByBusinessIdOrderByCategoryAscTitleAsc(
                        businessAId, java.util.EnumSet.of(SyncStatus.NOT_SYNCED)),
                articleA.getId(), articleB.getId());
        assertThat(kbArticles.findPendingSyncByBusinessIdOrderByCategoryAscTitleAsc(
                        businessAId, java.util.EnumSet.of(SyncStatus.NOT_SYNCED)))
                .extracting("id").doesNotContain(syncedA.getId());

        assertThat(kbArticles.findByIdAndBusinessId(articleA.getId(), businessAId)).isPresent();
        assertThat(kbArticles.findByIdAndBusinessId(articleA.getId(), businessBId)).isEmpty();
        assertThat(kbArticles.findByIdAndBusinessId(articleB.getId(), businessBId)).isPresent();
        assertThat(kbArticles.findByIdAndBusinessId(articleB.getId(), businessAId)).isEmpty();
    }

    @Test
    @DisplayName("KbRequestRepository: business_id scoping never crosses businesses, for the list, count, and single-lookup methods")
    void kbRequestRepositoryIsolation() {
        KbRequest requestA = kbRequests.save(KbRequest.builder().businessId(businessAId).question("Q-A")
                .target(KbRequestTarget.KB).status(KbRequestStatus.OPEN).requestedBy("manager").build());
        KbRequest requestB = kbRequests.save(KbRequest.builder().businessId(businessBId).question("Q-B")
                .target(KbRequestTarget.KB).status(KbRequestStatus.OPEN).requestedBy("manager").build());
        kbRequests.save(KbRequest.builder().businessId(businessBId).question("Q-B2")
                .target(KbRequestTarget.KB).status(KbRequestStatus.OPEN).requestedBy("manager").build());

        assertIds(kbRequests.findAllByBusinessIdOrderByCreatedAtDesc(businessAId), requestA.getId(), requestB.getId());
        assertIds(kbRequests.findAllByBusinessIdOrderByCreatedAtDesc(businessBId), requestB.getId(), requestA.getId());

        assertThat(kbRequests.countByBusinessIdAndStatus(businessAId, KbRequestStatus.OPEN)).isEqualTo(1L);
        assertThat(kbRequests.countByBusinessIdAndStatus(businessBId, KbRequestStatus.OPEN)).isEqualTo(2L);

        assertThat(kbRequests.findByIdAndBusinessId(requestA.getId(), businessAId)).isPresent();
        assertThat(kbRequests.findByIdAndBusinessId(requestA.getId(), businessBId)).isEmpty();
        assertThat(kbRequests.findByIdAndBusinessId(requestB.getId(), businessBId)).isPresent();
        assertThat(kbRequests.findByIdAndBusinessId(requestB.getId(), businessAId)).isEmpty();
    }

    @Test
    @DisplayName("RagDocumentRepository: business_id scoping never crosses businesses, for list and single-lookup")
    void ragDocumentRepositoryIsolation() {
        RagDocument docA = ragDocuments.save(ragDoc(businessAId, "a.txt", RagDocumentStatus.INDEXED));
        RagDocument docB = ragDocuments.save(ragDoc(businessBId, "b.txt", RagDocumentStatus.INDEXED));
        RagDocument pendingA = ragDocuments.save(ragDoc(businessAId, "pending-a.txt", RagDocumentStatus.PENDING));

        assertIds(ragDocuments.findAllByBusinessIdOrderByCreatedAtDesc(businessAId), docA.getId(), docB.getId());
        assertThat(ragDocuments.findAllByBusinessIdOrderByCreatedAtDesc(businessAId))
                .extracting("id").contains(pendingA.getId());
        assertIds(ragDocuments.findAllByBusinessIdOrderByCreatedAtDesc(businessBId), docB.getId(), docA.getId());

        assertIds(ragDocuments.findByBusinessIdAndStatusOrderByCreatedAtDesc(businessAId, RagDocumentStatus.INDEXED),
                docA.getId(), docB.getId());
        assertThat(ragDocuments.findByBusinessIdAndStatusOrderByCreatedAtDesc(businessAId, RagDocumentStatus.INDEXED))
                .extracting("id").doesNotContain(pendingA.getId());

        assertThat(ragDocuments.findByIdAndBusinessId(docA.getId(), businessAId)).isPresent();
        assertThat(ragDocuments.findByIdAndBusinessId(docA.getId(), businessBId)).isEmpty();
        assertThat(ragDocuments.findByIdAndBusinessId(docB.getId(), businessBId)).isPresent();
        assertThat(ragDocuments.findByIdAndBusinessId(docB.getId(), businessAId)).isEmpty();
    }

    /**
     * The most important isolation check in this file: {@code RagChunkRepository#searchNearest}
     * powers the live chat assistant's document retrieval. Business B's chunk is deliberately given
     * the exact query vector (distance 0 — the objectively nearest match in the whole table) while
     * Business A's chunk is given a different-but-still-in-range vector, so a business-A search that
     * returned B's chunk would show up as a wrong (nearer) result, not just a missing one.
     */
    @Test
    @DisplayName("RagChunkRepository: vector search never returns another business's chunk, even when it's the nearest match")
    void ragChunkVectorSearchIsolation() {
        RagDocument docA = ragDocuments.save(ragDoc(businessAId, "policy-a.txt", RagDocumentStatus.INDEXED));
        RagDocument docB = ragDocuments.save(ragDoc(businessBId, "policy-b.txt", RagDocumentStatus.INDEXED));

        RagChunk chunkA = ragChunks.save(RagChunk.builder().documentId(docA.getId()).ordinal(0)
                .chunkText("Business A's confidential refund policy").charStart(0).charEnd(10)
                .contentSha256("sha-a").status(RagChunkStatus.INDEXED).build());
        RagChunk chunkB = ragChunks.save(RagChunk.builder().documentId(docB.getId()).ordinal(0)
                .chunkText("Business B's confidential refund policy").charStart(0).charEnd(10)
                .contentSha256("sha-b").status(RagChunkStatus.INDEXED).build());

        // One-hot vectors, not all-zero/all-equal: cosine distance is undefined (NaN, matches
        // nothing) for a zero-magnitude vector, and scale-invariant for parallel same-direction
        // vectors, so those would either falsely match nothing or fail to distinguish "nearest" at
        // all. Orthogonal one-hot vectors give a real, well-defined distance (0 vs 1.0).
        String queryVec = vectorLiteral(0);
        ragChunks.updateEmbedding(chunkA.getId(), vectorLiteral(1)); // orthogonal — in range, not the nearest
        ragChunks.updateEmbedding(chunkB.getId(), queryVec);         // identical to the query — the true nearest

        List<ChunkMatch> hitsForA = ragChunks.searchNearest(queryVec, 2.0, 5, businessAId);
        assertThat(hitsForA).extracting("id").containsExactly(chunkA.getId());

        List<ChunkMatch> hitsForB = ragChunks.searchNearest(queryVec, 2.0, 5, businessBId);
        assertThat(hitsForB).extracting("id").containsExactly(chunkB.getId());
    }

    private static RagDocument ragDoc(Long businessId, String filename, RagDocumentStatus status) {
        return RagDocument.builder().businessId(businessId).filename(filename).sourceType("TEXT")
                .extractedText("x").status(status).uploadedBy("owner").build();
    }

    /** A 1024-dim one-hot vector literal (1.0 at {@code hotIndex}, 0.0 elsewhere) — gives a
     * well-defined, non-degenerate cosine distance, unlike an all-zero (undefined/NaN) or
     * all-equal (scale-invariant, indistinguishable direction) vector. */
    private static String vectorLiteral(int hotIndex) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 1024; i++) {
            if (i > 0) sb.append(',');
            sb.append(i == hotIndex ? "1.0" : "0.0");
        }
        return sb.append(']').toString();
    }

    @Test
    @DisplayName("RagAgentConfigRepository: the active config never crosses businesses")
    void ragAgentConfigRepositoryIsolation() {
        // Business A already has a real, permanently-seeded active config (V25) — deactivate it
        // first so this test's own row can become active without tripping the partial unique index
        // (business_id, active) WHERE active. Rolled back with everything else in this test.
        ragAgentConfigs.deactivateAll(businessAId);

        RagAgentConfig configA = ragAgentConfigs.save(RagAgentConfig.builder().version(9001).businessId(businessAId)
                .systemPrompt("A's prompt").model("claude-haiku-4-5").temperature(BigDecimal.ZERO).k(6)
                .distanceThreshold(new BigDecimal("0.600")).active(true).build());
        RagAgentConfig configB = ragAgentConfigs.save(RagAgentConfig.builder().version(9002).businessId(businessBId)
                .systemPrompt("B's prompt").model("claude-haiku-4-5").temperature(BigDecimal.ZERO).k(6)
                .distanceThreshold(new BigDecimal("0.600")).active(true).build());

        assertThat(ragAgentConfigs.findByBusinessIdAndActiveTrue(businessAId)).isPresent()
                .get().extracting(RagAgentConfig::getVersion).isEqualTo(configA.getVersion());
        assertThat(ragAgentConfigs.findByBusinessIdAndActiveTrue(businessBId)).isPresent()
                .get().extracting(RagAgentConfig::getVersion).isEqualTo(configB.getVersion());
    }

    @Test
    @DisplayName("RagRedactionAuditRepository: the audit trail never crosses businesses")
    void ragRedactionAuditRepositoryIsolation() {
        RagRedactionAudit auditA = ragRedactionAudits.save(RagRedactionAudit.builder().businessId(businessAId)
                .documentId(101L).filename("deleted-a.txt").chunkCount(3).deletedBy("owner").build());
        RagRedactionAudit auditB = ragRedactionAudits.save(RagRedactionAudit.builder().businessId(businessBId)
                .documentId(102L).filename("deleted-b.txt").chunkCount(2).deletedBy("owner").build());

        assertIds(ragRedactionAudits.findAllByBusinessIdOrderByDeletedAtDesc(businessAId),
                auditA.getId(), auditB.getId());
        assertIds(ragRedactionAudits.findAllByBusinessIdOrderByDeletedAtDesc(businessBId),
                auditB.getId(), auditA.getId());
    }

    @Test
    @DisplayName("RagSuggestionCacheRepository: the composite (business, language) key never crosses businesses")
    void ragSuggestionCacheRepositoryIsolation() {
        ragSuggestionCache.save(RagSuggestionCache.builder().businessId(businessAId).language("EN")
                .signature("sig-a").payload("{\"topics\":[]}").generatedAt(Instant.now()).build());
        ragSuggestionCache.save(RagSuggestionCache.builder().businessId(businessBId).language("EN")
                .signature("sig-b").payload("{\"topics\":[]}").generatedAt(Instant.now()).build());

        assertThat(ragSuggestionCache.findById(new RagSuggestionCacheId(businessAId, "EN")))
                .isPresent().get().extracting(RagSuggestionCache::getSignature).isEqualTo("sig-a");
        assertThat(ragSuggestionCache.findById(new RagSuggestionCacheId(businessBId, "EN")))
                .isPresent().get().extracting(RagSuggestionCache::getSignature).isEqualTo("sig-b");
        assertThat(ragSuggestionCache.findById(new RagSuggestionCacheId(businessAId, "RU"))).isEmpty();
    }

    @Test
    @DisplayName("SmsMessageRepository: conversationSummaries (native SQL) never surfaces another business's phone number")
    void smsMessageConversationSummariesIsolation() {
        String phoneA = "+15550001111";
        String phoneB = "+15550002222";
        smsMessages.save(SmsMessage.builder().businessId(businessAId).direction("OUTBOUND")
                .phoneNumber(phoneA).body("hi from A").status("SENT").build());
        smsMessages.save(SmsMessage.builder().businessId(businessBId).direction("OUTBOUND")
                .phoneNumber(phoneB).body("hi from B").status("SENT").build());

        List<String> phonesForA = smsMessages.conversationSummaries(businessAId).stream()
                .map(SmsMessageRepository.ConversationSummaryProjection::getPhoneNumber).toList();
        assertThat(phonesForA).contains(phoneA).doesNotContain(phoneB);

        List<String> phonesForB = smsMessages.conversationSummaries(businessBId).stream()
                .map(SmsMessageRepository.ConversationSummaryProjection::getPhoneNumber).toList();
        assertThat(phonesForB).contains(phoneB).doesNotContain(phoneA);
    }

    @Test
    @DisplayName("SmsMessageRepository: search never returns another business's messages")
    void smsMessageSearchIsolation() {
        String phoneA = "+15550003333";
        String phoneB = "+15550004444";
        SmsMessage messageA = smsMessages.save(SmsMessage.builder().businessId(businessAId).direction("OUTBOUND")
                .phoneNumber(phoneA).body("isolation-test-search-marker").status("SENT").build());
        SmsMessage messageB = smsMessages.save(SmsMessage.builder().businessId(businessBId).direction("OUTBOUND")
                .phoneNumber(phoneB).body("isolation-test-search-marker").status("SENT").build());

        var resultsA = smsMessages.search(businessAId, null, null, null,
                PageRequest.of(0, 100)).getContent();
        assertThat(resultsA).extracting(SmsMessage::getId).contains(messageA.getId()).doesNotContain(messageB.getId());

        var resultsB = smsMessages.search(businessBId, null, null, null,
                PageRequest.of(0, 100)).getContent();
        assertThat(resultsB).extracting(SmsMessage::getId).contains(messageB.getId()).doesNotContain(messageA.getId());
    }

    @Test
    @DisplayName("SmsReplyFlowRepository: due-send/reply-lookup queries never cross businesses")
    void smsReplyFlowRepositoryIsolation() {
        String phoneA = "+15550005555";
        String phoneB = "+15550006666";
        Instant now = Instant.now();
        SmsReplyFlow flowA = smsReplyFlows.save(SmsReplyFlow.builder().businessId(businessAId)
                .automationKey("checkout_review_request").phoneNumber(phoneA)
                .state(SmsReplyFlow.STATE_AWAITING_SEND).sendDueAt(now.minusSeconds(60)).build());
        SmsReplyFlow flowB = smsReplyFlows.save(SmsReplyFlow.builder().businessId(businessBId)
                .automationKey("checkout_review_request").phoneNumber(phoneB)
                .state(SmsReplyFlow.STATE_AWAITING_SEND).sendDueAt(now.minusSeconds(60)).build());

        assertIds(smsReplyFlows.findByBusinessIdAndStateAndSendDueAtBefore(
                        businessAId, SmsReplyFlow.STATE_AWAITING_SEND, now),
                flowA.getId(), flowB.getId());
        assertIds(smsReplyFlows.findByBusinessIdAndStateAndSendDueAtBefore(
                        businessBId, SmsReplyFlow.STATE_AWAITING_SEND, now),
                flowB.getId(), flowA.getId());

        assertThat(smsReplyFlows.findFirstByBusinessIdAndPhoneNumberAndStateOrderByCreatedAtDesc(
                businessAId, phoneA, SmsReplyFlow.STATE_AWAITING_SEND)).isPresent();
        assertThat(smsReplyFlows.findFirstByBusinessIdAndPhoneNumberAndStateOrderByCreatedAtDesc(
                businessBId, phoneA, SmsReplyFlow.STATE_AWAITING_SEND)).isEmpty();

        assertThat(smsReplyFlows.findByIdAndBusinessId(flowA.getId(), businessAId)).isPresent();
        assertThat(smsReplyFlows.findByIdAndBusinessId(flowA.getId(), businessBId)).isEmpty();
    }

    @Test
    @DisplayName("SameDayRebookingSendRepository: findByBusinessIdAndStateAndSendDueAtBefore never crosses businesses (tasks.md 3.7)")
    void sameDayRebookingSendRepositoryIsolation() {
        Instant now = Instant.now();
        SameDayRebookingSend sendA = sameDayRebookingSends.save(SameDayRebookingSend.builder()
                .businessId(businessAId).phoneNumber("+15550007777").squareCustomerId("cust-a")
                .squarePaymentId("pay-a-" + System.nanoTime()).sendDueAt(now.minusSeconds(60))
                .promoExpiresAt(now.plusSeconds(3600)).state(SameDayRebookingSend.STATE_AWAITING_SEND).build());
        SameDayRebookingSend sendB = sameDayRebookingSends.save(SameDayRebookingSend.builder()
                .businessId(businessBId).phoneNumber("+15550008888").squareCustomerId("cust-b")
                .squarePaymentId("pay-b-" + System.nanoTime()).sendDueAt(now.minusSeconds(60))
                .promoExpiresAt(now.plusSeconds(3600)).state(SameDayRebookingSend.STATE_AWAITING_SEND).build());

        assertIds(sameDayRebookingSends.findByBusinessIdAndStateAndSendDueAtBefore(
                        businessAId, SameDayRebookingSend.STATE_AWAITING_SEND, now),
                sendA.getId(), sendB.getId());
        assertIds(sameDayRebookingSends.findByBusinessIdAndStateAndSendDueAtBefore(
                        businessBId, SameDayRebookingSend.STATE_AWAITING_SEND, now),
                sendB.getId(), sendA.getId());
    }

    /**
     * 2026-08-19 cross-tenant fix (V113): ad_spend_entries had no business_id column at all until
     * this migration, so MarketingAdsReportController's GET/PUT/DELETE /spend endpoints operated
     * on every business's entries — a business could read, edit, or delete another business's ad
     * spend by guessing a sequential id. Same shared-slug-across-businesses shape as
     * suspiciousBookingClearanceCrossTenantFix above: both businesses log spend under the literal
     * slug "mani" (a plain trusted string, not a business-scoped FK — see AdSpendEntry's own doc),
     * and only business_id keeps them apart.
     */
    @Test
    @DisplayName("2026-08-19: AdSpendEntryRepository — business-scoped lookup/update/delete, "
            + "same landing_page_slug string reused across businesses")
    void adSpendEntryCrossTenantFix() {
        LocalDate periodStart = LocalDate.of(2026, 8, 1);
        LocalDate periodEnd = LocalDate.of(2026, 8, 7);
        AdSpendEntry entryA = adSpendEntries.save(AdSpendEntry.builder()
                .businessId(businessAId).landingPageSlug("mani")
                .periodStart(periodStart).periodEnd(periodEnd)
                .amountSpent(new BigDecimal("100.00")).enteredBy("owner-a").build());
        AdSpendEntry entryB = adSpendEntries.save(AdSpendEntry.builder()
                .businessId(businessBId).landingPageSlug("mani")
                .periodStart(periodStart).periodEnd(periodEnd)
                .amountSpent(new BigDecimal("200.00")).enteredBy("owner-b").build());

        assertIds(adSpendEntries.findOverlapping("mani", periodStart, periodEnd, businessAId),
                entryA.getId(), entryB.getId());
        assertIds(adSpendEntries.findOverlapping("mani", periodStart, periodEnd, businessBId),
                entryB.getId(), entryA.getId());
        assertIds(adSpendEntries.findByLandingPageSlugAndBusinessIdOrderByPeriodStartDesc("mani", businessAId),
                entryA.getId(), entryB.getId());

        assertThat(adSpendEntries.findByIdAndBusinessId(entryA.getId(), businessAId)).isPresent();
        assertThat(adSpendEntries.findByIdAndBusinessId(entryA.getId(), businessBId)).isEmpty();
        assertThat(adSpendEntries.existsByIdAndBusinessId(entryA.getId(), businessBId)).isFalse();

        // Business B can't delete business A's entry by guessing its id.
        adSpendEntries.deleteByIdAndBusinessId(entryA.getId(), businessBId);
        assertThat(adSpendEntries.findByIdAndBusinessId(entryA.getId(), businessAId)).isPresent();

        adSpendEntries.deleteByIdAndBusinessId(entryA.getId(), businessAId);
        assertThat(adSpendEntries.findByIdAndBusinessId(entryA.getId(), businessAId)).isEmpty();
        // Business A's delete never touched business B's same-slug entry.
        assertThat(adSpendEntries.findByIdAndBusinessId(entryB.getId(), businessBId)).isPresent();
    }
}
