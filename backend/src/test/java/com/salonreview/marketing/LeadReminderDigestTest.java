package com.salonreview.marketing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.domain.Business;
import com.salonreview.repo.BusinessRepository;
import com.salonreview.telegram.TelegramNotificationService;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class LeadReminderDigestTest {

    private static final ZoneId PT = ZoneId.of("America/Los_Angeles");

    @Autowired private BusinessRepository businesses;
    @Autowired private JdbcTemplate jdbc;
    private TelegramNotificationService telegram;
    private LeadFollowUpService service;
    private long b;

    @BeforeEach
    void setUp() {
        b = businesses.save(Business.builder().name("Digest").shortCode("dg-" + System.nanoTime()).timezone("UTC").active(true).build()).getId();
        LeadIntakeService intake = mock(LeadIntakeService.class);
        when(intake.enabledBusinesses()).thenReturn(Set.of(b));
        ConsultationCatalog catalog = mock(ConsultationCatalog.class);
        when(catalog.ids(b)).thenReturn(Set.of());
        CurrentBusinessContext ctx = mock(CurrentBusinessContext.class);
        when(ctx.id()).thenReturn(b);
        telegram = mock(TelegramNotificationService.class);
        when(telegram.sendPlainText(anyLong(), anyString())).thenReturn(true);
        service = new LeadFollowUpService(jdbc, ctx, telegram, null, null, intake, catalog, null, null, null, null, null, "https://x");
    }

    private long dueLead(String name, String status, int minutesAgo) {
        long id = jdbc.queryForObject("""
                INSERT INTO lead_intake (business_id, channel, source, phone_number, name, outcome, status)
                VALUES (?, 'Form', 'Google', ?, ?, 'MATCHED', ?) RETURNING id
                """, Long.class, b, "+1555" + (long) (Math.random() * 9_000_000 + 1_000_000), name, status);
        jdbc.update("INSERT INTO lead_reminders (business_id, lead_id, due_at, origin, created_by) VALUES (?, ?, now() - ?::interval, 'MANUAL', 'test')",
                b, id, minutesAgo + " minutes");
        return id;
    }

    private int unfired() {
        return jdbc.queryForObject("SELECT count(*) FROM lead_reminders WHERE business_id = ? AND fired_at IS NULL", Integer.class, b);
    }

    private static ZonedDateTime at(int hour, int minute) {
        java.time.LocalDate tomorrow = java.time.LocalDate.now(PT).plusDays(1);
        return ZonedDateTime.of(tomorrow.getYear(), tomorrow.getMonthValue(), tomorrow.getDayOfMonth(), hour, minute, 0, 0, PT);
    }

    @Test
    void outsideWindowHoldsRemindersWithoutSending() {
        dueLead("Held", "CONTACTED", 90);
        service.sendDueReminders(at(12, 0));
        verify(telegram, never()).sendPlainText(anyLong(), anyString());
        assertThat(unfired()).isEqualTo(1);
    }

    @Test
    void oneMessagePerBusinessAndEachReminderFiresOnce() {
        dueLead("Anna", "CONTACTED", 90);
        dueLead("Bella", "NEW", 30);
        service.sendDueReminders(at(11, 0));
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(telegram, times(1)).sendPlainText(org.mockito.ArgumentMatchers.eq(b), text.capture());
        assertThat(text.getValue()).contains("2 overdue").contains("Anna").contains("Bella").contains("/admin/leads?business=" + b);
        assertThat(unfired()).isZero();
        service.sendDueReminders(at(14, 0));
        verify(telegram, times(1)).sendPlainText(anyLong(), anyString());
    }

    @Test
    void failedDeliveryRetriesWithinHourAndKeepsReminderUnfiredUntilDelivered() {
        dueLead("Anna", "CONTACTED", 90);
        when(telegram.sendPlainText(anyLong(), anyString())).thenReturn(false, true);
        service.sendDueReminders(at(8, 0));
        assertThat(unfired()).isEqualTo(1);
        service.sendDueReminders(at(8, 5));
        verify(telegram, times(2)).sendPlainText(anyLong(), anyString());
        assertThat(unfired()).isZero();
        service.sendDueReminders(at(8, 10));
        verify(telegram, times(2)).sendPlainText(anyLong(), anyString());
    }

    @Test
    void doneAndSpamLeadsAreNotIncluded() {
        long done = dueLead("Done", "CONTACTED", 90);
        jdbc.update("UPDATE lead_reminders SET done_at = now() WHERE lead_id = ?", done);
        dueLead("Spammer", "SPAM", 90);
        service.sendDueReminders(at(8, 0));
        verify(telegram, never()).sendPlainText(anyLong(), anyString());
    }

}
