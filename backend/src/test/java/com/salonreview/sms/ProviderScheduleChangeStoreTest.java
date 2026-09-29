package com.salonreview.sms;

import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.domain.Business;
import com.salonreview.repo.BusinessRepository;
import com.salonreview.repo.ProviderScheduleClosureAlertRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.config.TaskManagementConfigUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.salonreview.sms.ProviderScheduleTestFixtures.*;
import static org.assertj.core.api.Assertions.*;

/** Uses only the fresh pgvector/pg16 test database. No Telegram/Square credentials or timers. */
@SpringBootTest
@Transactional
class ProviderScheduleChangeStoreTest {
    @Autowired ProviderScheduleChangeStore store;
    @Autowired CurrentBusinessContext context;
    @Autowired BusinessRepository businesses;
    @Autowired ProviderScheduleClosureAlertRepository alerts;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApplicationContext applicationContext;
    @Autowired PlatformTransactionManager transactions;
    private final List<Long> fixtures = new ArrayList<>();
    private Long businessId;

    @BeforeEach
    void createBusiness() {
        businessId = business(false);
        assertThat(applicationContext.containsBean(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME)).isFalse();
    }

    private Long business(boolean shadow) {
        Long id = businesses.saveAndFlush(Business.builder().name("Schedule test")
                .shortCode("schedule-" + UUID.randomUUID()).timezone("America/Los_Angeles").active(true).build()).getId();
        fixtures.add(id);
        jdbc.update("INSERT INTO provider_schedule_closure_alert_config(business_id, observation_only) VALUES (?, ?)", id, shadow);
        jdbc.update("INSERT INTO sms_automation(business_id, automation_key, enabled) VALUES (?, 'provider_schedule_closure_alert', true)", id);
        return id;
    }

    @AfterEach
    void removeOnlyOwnFixtures() {
        for (Long id : fixtures) {
            jdbc.update("DELETE FROM provider_schedule_closure_alert WHERE business_id = ?", id);
            jdbc.update("DELETE FROM provider_schedule_change_event WHERE business_id = ?", id);
            jdbc.update("DELETE FROM provider_availability_state WHERE business_id = ?", id);
            jdbc.update("DELETE FROM provider_availability_observation WHERE business_id = ?", id);
            jdbc.update("DELETE FROM provider_schedule_closure_alert_config WHERE business_id = ?", id);
            jdbc.update("DELETE FROM sms_automation WHERE business_id = ?", id);
            jdbc.update("DELETE FROM business WHERE id = ?", id);
        }
        fixtures.clear();
        assertThat(context.isPopulated()).isFalse();
    }

    @Test
    void persistsRealEmptyObservationAndFreezesAvailableEvidenceUntilElapsedConfirmation() {
        observe(0, true, false);
        assertThat(latest()).isEqualTo(observation(BASE, true, false));
        observe(10, false, false);
        assertThat(eventStatus()).isEqualTo("PENDING");
        assertThat(count("provider_schedule_closure_alert")).isZero();
        observe(20, false, false);
        assertThat(eventStatus()).isEqualTo("PENDING");
        observe(30, false, false);
        assertThat(eventStatus()).isEqualTo("CONFIRMED");
        assertThat(count("provider_schedule_closure_alert")).isEqualTo(1);
        var history = context.runAsAndGet(businessId, () -> store.history(businessId));
        assertThat(history.events()).hasSize(1);
        assertThat(history.events().getFirst().windowMinutes()).isEqualTo(240);
        assertThat(history.events().getFirst().confirmedAt()).isEqualTo(at(30));
        assertThat(history.events().getFirst().deliveryStatus()).isEqualTo("PENDING");
    }

    @Test
    void observationModeRecordsConfirmationWithoutCreatingOutbox() {
        jdbc.update("UPDATE provider_schedule_closure_alert_config SET observation_only = true WHERE business_id = ?", businessId);
        observe(0, true, true);
        observe(10, false, true);
        observe(30, false, true);
        assertThat(eventStatus()).isEqualTo("OBSERVED");
        assertThat(count("provider_schedule_closure_alert")).isZero();
    }

    @Test
    void returningAvailabilityResolvesCandidateAndStaleEvidenceExpires() {
        observe(0, true, false);
        observe(10, false, false);
        observe(20, true, false);
        assertThat(eventStatus()).isEqualTo("RESOLVED");
        observe(30, false, false);
        observe(76, false, false);
        assertThat(eventStatus()).isEqualTo("EXPIRED");
        assertThat(count("provider_schedule_closure_alert")).isZero();
    }

    @Test
    void failedReadPreservesLastValidBaselineAndDoesNotConfirmAnything() {
        observe(0, true, false);
        observe(10, false, false);
        context.runAs(businessId, () -> store.failure(businessId, TEAM, at(30), "INCOMPLETE_BOOKINGS"));
        assertThat(latest().capturedAt()).isEqualTo(at(10));
        assertThat(eventStatus()).isEqualTo("PENDING");
        assertThat(count("provider_schedule_closure_alert")).isZero();
        context.runAs(businessId, () -> store.failure(businessId, TEAM, at(60), "INVALID_RESPONSE"));
        assertThat(eventStatus()).isEqualTo("EXPIRED");
    }

    @Test
    void duplicateAndOutOfOrderPollsDoNotChangeHeadOrConfirmationTime() {
        observe(0, true, false);
        observe(10, false, false);
        observe(10, true, false);
        observe(5, true, false);
        assertThat(latest().capturedAt()).isEqualTo(at(10));
        assertThat(latest().primarySlots()).isEmpty();
        assertThat(count("provider_availability_observation")).isEqualTo(2);
        observe(30, false, false);
        assertThat(eventStatus()).isEqualTo("CONFIRMED");
    }

    @Test
    void changedProbePolicySuppressesPreviousCandidateRatherThanComparingDifferentConditions() {
        observe(0, true, false);
        observe(10, false, false);
        observe(30, false, true);
        assertThat(eventStatus()).isEqualTo("SUPPRESSED");
        assertThat(count("provider_schedule_closure_alert")).isZero();
    }

    @Test
    void deliveryClaimIsSingleUseAndCounterIncludesOnlyConfirmedDelivery() {
        confirm();
        long id = alertId();
        assertThat(claim(id, at(30))).isTrue();
        assertThat(claim(id, at(30))).isFalse();
        assertThat(alerts.countByBusinessIdAndDeliveryStatusAndDeliveredAtAfter(businessId, "SENT", BASE)).isZero();
        context.runAs(businessId, () -> store.delivered(businessId, id, "SENT", 123L, at(30)));
        assertThat(alerts.countByBusinessIdAndDeliveryStatusAndDeliveredAtAfter(businessId, "SENT", BASE)).isEqualTo(1);
        observe(40, true, false);
        observe(50, false, false);
        observe(70, false, false);
        assertThat(count("provider_schedule_closure_alert")).isEqualTo(1);
        assertThat(deliveryStatus()).isEqualTo("SENT");
        assertThat(claim(id, at(70))).isFalse();
    }

    @Test
    void confirmedEventIsBlockedByFailureUntilFreshSuccessfulEvidence() {
        confirm();
        long id = alertId();
        context.runAs(businessId, () -> store.failure(businessId, "business", at(31), "INVALID_RESPONSE"));
        assertThat(claim(id, at(31))).isFalse();
        observe(32, false, false);
        assertThat(claim(id, at(32))).isTrue();
        assertThat(context.runAsAndGet(businessId, () -> store.history(businessId)).providers())
                .noneMatch(provider -> provider.teamMemberId().equals("business"));
    }

    @Test
    void crashAfterClaimBecomesUnknownAndNeverAutomaticallySendsAgain() {
        confirm();
        long id = alertId();
        assertThat(claim(id, at(30))).isTrue();
        context.runAs(businessId, () -> store.recoverAmbiguousAttempts(businessId, at(33)));
        assertThat(deliveryStatus()).isEqualTo("UNKNOWN");
        observe(40, false, false);
        assertThat(deliveryStatus()).isEqualTo("UNKNOWN");
        assertThat(claim(id, at(40))).isFalse();
    }

    @Test
    void definiteFailuresRequireFreshConfirmationAndStopAfterThreeAttempts() {
        confirm();
        long id = alertId();
        for (int minute : new int[]{30, 40, 50}) {
            if (minute > 30) observe(minute, false, false);
            assertThat(claim(id, at(minute))).isTrue();
            context.runAs(businessId, () -> store.delivered(businessId, id, "FAILED", null, at(minute)));
            assertThat(claim(id, at(minute))).isFalse();
        }
        observe(60, false, false);
        assertThat(deliveryStatus()).isEqualTo("FAILED");
        assertThat(claim(id, at(60))).isFalse();
        assertThat(alerts.countByBusinessIdAndDeliveryStatusAndDeliveredAtAfter(businessId, "SENT", BASE)).isZero();
    }

    @Test
    void resolvedUnsentEventCanBeConfirmedAgainWithoutLosingQueuePolicy() {
        confirm();
        observe(40, true, false);
        assertThat(deliveryStatus()).isEqualTo("SUPPRESSED");
        observe(50, false, false);
        observe(70, false, false);
        assertThat(eventStatus()).isEqualTo("CONFIRMED");
        assertThat(deliveryStatus()).isEqualTo("PENDING");
        assertThat(count("provider_schedule_closure_alert")).isEqualTo(1);
        assertThat(claim(alertId(), at(70))).isTrue();
    }

    @Test
    void settingsChangeAndDisabledAutomationAreCheckedAgainAtomicallyAtClaim() {
        confirm();
        long id = alertId();
        jdbc.update("UPDATE provider_schedule_closure_alert_config SET minimum_loss_window_minutes = 300 WHERE business_id = ?", businessId);
        assertThat(claim(id, at(30))).isFalse();
        jdbc.update("UPDATE provider_schedule_closure_alert_config SET minimum_loss_window_minutes = 240, observation_only = true WHERE business_id = ?", businessId);
        assertThat(claim(id, at(30))).isFalse();
        jdbc.update("UPDATE provider_schedule_closure_alert_config SET observation_only = false WHERE business_id = ?", businessId);
        jdbc.update("UPDATE sms_automation SET enabled = false WHERE business_id = ? AND automation_key = 'provider_schedule_closure_alert'", businessId);
        assertThat(claim(id, at(30))).isFalse();
    }

    @Test
    void windowThatReachesCutoffWhileWaitingInQueueIsNotDispatched() {
        var first = BASE.plus(Duration.ofMinutes(153));
        var c = contract(false);
        for (int minute : new int[]{0, 10, 30}) {
            var evidence = observation(at(minute), c, minute == 0 ? grid(first, 9) : List.of(),
                    minute == 0 ? List.of(new ProviderAvailabilityObservation.Slot(first, first.plusSeconds(7200), List.of())) : List.of(), List.of());
            context.runAs(businessId, () -> store.observe(businessId, "Tatiana", evidence));
        }
        assertThat(eventStatus()).isEqualTo("CONFIRMED");
        assertThat(context.runAsAndGet(businessId, () -> store.pendingAlerts(businessId, at(34)))).hasSize(1);
        assertThat(claim(alertId(), at(34))).isFalse();
    }

    @Test
    void changingBusinessTimezoneInvalidatesQueuedDateAndMessage() {
        confirm();
        jdbc.update("UPDATE business SET timezone = 'America/New_York' WHERE id = ?", businessId);
        assertThat(claim(alertId(), at(30))).isFalse();
    }

    @Test
    void sameSquareTeamIdInTwoBusinessesHasIndependentStateHistoryAndClaims() {
        confirm();
        long other = business(false);
        context.runAs(other, () -> store.observe(other, "Another provider", observation(BASE, true, false)));
        assertThat(context.runAsAndGet(other, () -> store.latest(other, TEAM)).primarySlots()).hasSize(9);
        assertThat(context.runAsAndGet(other, () -> store.history(other)).events()).isEmpty();
        assertThat(context.runAsAndGet(other, () -> store.claim(other, alertId(), at(30)))).isFalse();
        assertThatThrownBy(() -> context.runAs(other, () -> store.latest(businessId, TEAM)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.history(businessId)).isInstanceOf(IllegalStateException.class);
        assertThat(latest().primarySlots()).isEmpty();
    }

    @Test
    void retentionRemovesOnlyUnpinnedObservationHistoryAndKeepsLegacyAudit() {
        observe(0, true, false);
        observe(10, true, false);
        jdbc.update("INSERT INTO provider_schedule_closure_alert(business_id, team_member_id, slot_count, earliest_slot_at, latest_slot_at, sent_at) VALUES (?, ?, 1, ?, ?, ?)",
                businessId, TEAM, java.sql.Timestamp.from(BASE), java.sql.Timestamp.from(BASE), java.sql.Timestamp.from(BASE));
        context.runAs(businessId, () -> store.cleanHistory(businessId, BASE.plus(Duration.ofDays(100))));
        assertThat(count("provider_availability_observation")).isEqualTo(1);
        assertThat(latest().capturedAt()).isEqualTo(at(10));
        assertThat(deliveryStatus()).isEqualTo("LEGACY");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void failedCandidateInsertRollsBackObservationAndStateAsOneTransaction() {
        observe(0, true, false);
        assertThatThrownBy(() -> context.runAs(businessId,
                () -> store.observe(businessId, null, observation(at(10), false, false))))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(latest().capturedAt()).isEqualTo(BASE);
        assertThat(count("provider_availability_observation")).isEqualTo(1);
        assertThat(count("provider_schedule_change_event")).isZero();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentReplicasProduceOnlyOneConfirmationAndOneOutboxRow() throws Exception {
        observe(0, true, false);
        observe(10, false, false);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var tasks = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 2; i++) tasks.add(executor.submit(() -> {
                ready.countDown();
                try { assertThat(start.await(10, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException exception) { throw new RuntimeException(exception); }
                observe(30, false, false);
            }));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (var task : tasks) task.get(15, TimeUnit.SECONDS);
        }
        assertThat(count("provider_schedule_closure_alert")).isEqualTo(1);
        assertThat(count("provider_schedule_change_event")).isEqualTo(1);
        assertThat(count("provider_availability_observation")).isEqualTo(3);
        assertThat(eventStatus()).isEqualTo("CONFIRMED");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void deliveryWaitsForConcurrentFailureCommitBeforeEvaluatingEvidence() throws Exception {
        confirm();
        long id = alertId();
        var written = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var claiming = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var failure = executor.submit(() -> context.runAs(businessId,
                    () -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                        store.failure(businessId, TEAM, at(31), "INCOMPLETE_BOOKINGS");
                        written.countDown();
                        try { assertThat(release.await(10, TimeUnit.SECONDS)).isTrue(); }
                        catch (InterruptedException exception) { throw new RuntimeException(exception); }
                    })));
            try {
                assertThat(written.await(10, TimeUnit.SECONDS)).isTrue();
                var delivery = executor.submit(() -> {
                    claiming.countDown();
                    return claim(id, at(31));
                });
                assertThat(claiming.await(10, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> delivery.get(200, TimeUnit.MILLISECONDS))
                        .isInstanceOf(java.util.concurrent.TimeoutException.class);
                release.countDown();
                assertThat(delivery.get(10, TimeUnit.SECONDS)).isFalse();
                failure.get(10, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
        }
        assertThat(deliveryStatus()).isEqualTo("PENDING");
    }

    private void confirm() { observe(0, true, false); observe(10, false, false); observe(30, false, false); }
    private void observe(int minute, boolean available, boolean shadow) {
        context.runAs(businessId, () -> store.observe(businessId, "Tatiana", observation(at(minute), available, shadow)));
    }
    private ProviderAvailabilityObservation latest() { return context.runAsAndGet(businessId, () -> store.latest(businessId, TEAM)); }
    private boolean claim(long id, Instant at) { return context.runAsAndGet(businessId, () -> store.claim(businessId, id, at)); }
    private static Instant at(int minutes) { return BASE.plus(Duration.ofMinutes(minutes)); }
    private long count(String table) {
        // table names are fixed test literals, never user input; every query remains business-scoped.
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE business_id = ?", Long.class, businessId);
    }
    private long alertId() { return jdbc.queryForObject("SELECT id FROM provider_schedule_closure_alert WHERE business_id = ?", Long.class, businessId); }
    private String eventStatus() { return jdbc.queryForObject("SELECT status FROM provider_schedule_change_event WHERE business_id = ?", String.class, businessId); }
    private String deliveryStatus() { return jdbc.queryForObject("SELECT delivery_status FROM provider_schedule_closure_alert WHERE business_id = ?", String.class, businessId); }
}
