package com.salonreview.marketing;

import com.salonreview.config.InternalApiProperties;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/internal/google-ads")
public class GoogleAdsInternalController {

    private final GoogleAdsSyncService sync;
    private final GoogleAdsWeeklyReportService report;
    private final InternalApiProperties props;

    public GoogleAdsInternalController(GoogleAdsSyncService sync, GoogleAdsWeeklyReportService report, InternalApiProperties props) {
        this.sync = sync;
        this.report = report;
        this.props = props;
    }

    @PostMapping("/sync")
    public String sync(@RequestHeader(value = "X-Internal-Api-Key", required = false) String key,
                       @RequestParam(defaultValue = "90") int days) {
        auth(key);
        LocalDate today = LocalDate.now(java.time.ZoneId.of("America/Los_Angeles"));
        return sync.syncAll(today.minusDays(Math.max(1, Math.min(days, 400))), today);
    }

    @PostMapping("/report")
    public String report(@RequestHeader(value = "X-Internal-Api-Key", required = false) String key,
                         @RequestParam(defaultValue = "false") boolean send) {
        auth(key);
        return report.run(send);
    }

    private void auth(String key) {
        String expected = props.getKey();
        if (expected == null || expected.isBlank() || key == null
                || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), key.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
    }
}
