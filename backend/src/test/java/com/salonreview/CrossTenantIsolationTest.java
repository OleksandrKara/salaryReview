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
    @Autowired private PrepaidPackageRepository prepaidPackages;
    @Autowired private PrepaidRedemptionRepository prepaidRedemptions;
    @Autowired private ProviderVisitRepository providerVisits;
    @Autowired private StaffDocumentRepository staffDocuments;
    @Autowired private SopRepository sops;

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
}
