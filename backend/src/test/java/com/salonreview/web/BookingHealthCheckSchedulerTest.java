package com.salonreview.web;

import com.salonreview.domain.BookingHealthState;
import com.salonreview.repo.BookingHealthStateRepository;
import com.salonreview.telegram.TelegramNotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** One alert after two failures in a row, one recovery message, nothing for a single blip. */
class BookingHealthCheckSchedulerTest {

    private final Map<BookingHealthState.Key, BookingHealthState> store = new HashMap<>();
    private BookingHealthStateRepository repo;
    private TelegramNotificationService telegram;
    private BookingHealthCheckScheduler scheduler;

    @BeforeEach
    void setUp() {
        repo = mock(BookingHealthStateRepository.class);
        telegram = mock(TelegramNotificationService.class);
        when(repo.findById(any())).thenAnswer(inv -> Optional.ofNullable(store.get(inv.<BookingHealthState.Key>getArgument(0))));
        when(repo.save(any())).thenAnswer(inv -> {
            BookingHealthState s = inv.getArgument(0);
            store.put(new BookingHealthState.Key(s.getBusinessId(), s.getCheckKey()), s);
            return s;
        });
        scheduler = new BookingHealthCheckScheduler(repo, telegram, mock(HttpClient.class));
    }

    @Test
    void singleBlipIsSilent() {
        scheduler.record(2L, "procedure_menu", Optional.of("HTTP 502"));
        scheduler.record(2L, "procedure_menu", Optional.empty());
        verifyNoInteractions(telegram);
    }

    @Test
    void twoFailuresAlertOnceThenRecoveryMessage() {
        scheduler.record(2L, "procedure_menu", Optional.of("HTTP 502"));
        scheduler.record(2L, "procedure_menu", Optional.of("HTTP 502"));
        scheduler.record(2L, "procedure_menu", Optional.of("HTTP 502"));
        verify(telegram, times(1)).sendPlainAlert(eq(2L), contains("Online booking problem: HTTP 502"));
        scheduler.record(2L, "procedure_menu", Optional.empty());
        verify(telegram, times(1)).sendPlainAlert(eq(2L), contains("works again"));
        scheduler.record(2L, "procedure_menu", Optional.empty());
        verify(telegram, times(2)).sendPlainAlert(eq(2L), anyString());
    }
}
