package com.salonreview.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salonreview.domain.BookingHealthState;
import com.salonreview.repo.BookingHealthStateRepository;
import com.salonreview.telegram.TelegramNotificationService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Online booking health checks (owner request 2026-10-06): the PMU booking form once broke after a
 * change in Square ("Unable to load service catalog") and nobody knew until a client said so.
 * Every 15 minutes this calls the same public booking API the sites use and tells the business's
 * staff Telegram chat when a check has failed twice in a row, and again when it works.
 *
 * <p>Checks for business 2 (book.pmu-annakara.com serves both pmu-annakara.com's popups and its
 * own page): the procedure menu loads and lists PMU services, the consultation catalog loads,
 * online consultation times exist in the next 14 days, and a procedure's times load.
 */
@Component
public class BookingHealthCheckScheduler {

    private static final Logger log = LoggerFactory.getLogger(BookingHealthCheckScheduler.class);
    private static final int FAILS_BEFORE_ALERT = 2;
    private static final String USER_AGENT = "AnnaKaraBookingHealthCheck/1.0";

    /** Business id -> booking API base URL. */
    private static final Map<Long, String> TARGETS = Map.of(2L, "https://book.pmu-annakara.com");

    private final BookingHealthStateRepository repository;
    private final TelegramNotificationService telegram;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    @org.springframework.beans.factory.annotation.Autowired
    public BookingHealthCheckScheduler(BookingHealthStateRepository repository, TelegramNotificationService telegram) {
        this(repository, telegram, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL).build());
    }

    BookingHealthCheckScheduler(BookingHealthStateRepository repository, TelegramNotificationService telegram, HttpClient http) {
        this.repository = repository;
        this.telegram = telegram;
        this.http = http;
    }

    @Scheduled(fixedDelay = 900_000, initialDelay = 180_000)
    @SchedulerLock(name = "BookingHealthCheckScheduler_run", lockAtLeastFor = "PT1M", lockAtMostFor = "PT10M")
    public void run() {
        TARGETS.forEach((businessId, base) -> {
            try {
                runChecks(businessId, base);
            } catch (RuntimeException e) {
                log.warn("Booking health checks for business {} errored: {}", businessId, e.getMessage(), e);
            }
        });
    }

    void runChecks(Long businessId, String base) {
        Map<String, Supplier<Optional<String>>> checks = new LinkedHashMap<>();
        checks.put("procedure_menu", () -> checkMenu(base));
        checks.put("consultation_catalog", () -> get(base + "/api/pmu/catalog").map(e -> e));
        checks.put("consultation_times", () -> checkConsultationTimes(base));
        checks.put("procedure_times", () -> checkProcedureTimes(base));
        checks.forEach((key, check) -> record(businessId, key, check.get()));
    }

    private Optional<String> checkMenu(String base) {
        try {
            JsonNode menu = fetch(base + "/api/pmu/menu");
            for (JsonNode section : menu.path("sections")) {
                if ("pmu".equals(section.path("key").asText()) && section.path("groups").size() > 0) {
                    return Optional.empty();
                }
            }
            return Optional.of("the procedure menu has no permanent makeup services");
        } catch (Exception e) {
            return Optional.of("the procedure menu doesn't load: " + e.getMessage());
        }
    }

    private Optional<String> checkConsultationTimes(String base) {
        try {
            JsonNode res = fetch(base + "/api/pmu/availability/consultation/online-consultation?days=14");
            return res.path("slots").size() > 0 ? Optional.empty()
                    : Optional.of("no online consultation times in the next 14 days");
        } catch (Exception e) {
            return Optional.of("consultation times don't load: " + e.getMessage());
        }
    }

    private Optional<String> checkProcedureTimes(String base) {
        try {
            JsonNode menu = fetch(base + "/api/pmu/menu");
            String variation = null;
            for (JsonNode section : menu.path("sections")) {
                if (!"pmu".equals(section.path("key").asText())) continue;
                JsonNode artists = section.path("groups").path(0).path("services").path(0).path("artists");
                if (artists.size() > 0) variation = artists.path(0).path("variation_id").asText(null);
            }
            if (variation == null) return Optional.of("no procedure to check times for");
            fetch(base + "/api/pmu/availability/service/" + variation + "?days=14");
            return Optional.empty();
        } catch (Exception e) {
            return Optional.of("procedure times don't load: " + e.getMessage());
        }
    }

    private Optional<String> get(String url) {
        try {
            fetch(url);
            return Optional.empty();
        } catch (Exception e) {
            return Optional.of(url.replaceAll("^https?://[^/]+", "") + " doesn't load: " + e.getMessage());
        }
    }

    private JsonNode fetch(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(25))
                .header("User-Agent", USER_AGENT).header("Accept", "application/json").GET().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            String body = res.body() == null ? "" : res.body();
            throw new IllegalStateException("HTTP " + res.statusCode() + (body.isBlank() ? "" : " " + body.substring(0, Math.min(160, body.length()))));
        }
        return mapper.readTree(res.body());
    }

    /** One message when a check has failed {@link #FAILS_BEFORE_ALERT} times in a row, one when it
     * passes again after that; nothing for a single blip. */
    void record(Long businessId, String key, Optional<String> error) {
        BookingHealthState state = repository.findById(new BookingHealthState.Key(businessId, key))
                .orElseGet(() -> BookingHealthState.builder().businessId(businessId).checkKey(key).build());
        Instant now = Instant.now();
        if (error.isPresent()) {
            if (state.getFailCount() == 0) state.setFailingSince(now);
            state.setFailCount(state.getFailCount() + 1);
            state.setLastError(error.get());
            if (state.getFailCount() >= FAILS_BEFORE_ALERT && !state.isAlerted()) {
                telegram.sendPlainAlert(businessId, "⚠️ Online booking problem: " + error.get()
                        + ".\nClients may not be able to book on the website right now. If something was just changed in "
                        + "Square (a service renamed, deleted or re-created, an artist's schedule), that's the first thing to check."
                        + "\n\n-----\n\n⚠️ Проблема с онлайн-записью: " + error.get()
                        + ".\nКлиенты сейчас могут не суметь записаться на сайте. Если в Square что-то недавно меняли "
                        + "(переименовали, удалили или пересоздали услугу, расписание мастера), проверьте это в первую очередь.");
                state.setAlerted(true);
            }
        } else {
            if (state.isAlerted()) {
                telegram.sendPlainAlert(businessId, "✅ Online booking works again (" + key.replace('_', ' ') + ")."
                        + "\n\n-----\n\n✅ Онлайн-запись снова работает (" + key.replace('_', ' ') + ").");
            }
            state.setFailCount(0);
            state.setAlerted(false);
            state.setFailingSince(null);
            state.setLastError(null);
        }
        state.setUpdatedAt(now);
        repository.save(state);
    }
}
