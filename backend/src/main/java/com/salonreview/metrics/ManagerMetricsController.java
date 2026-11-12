package com.salonreview.metrics;

import com.salonreview.config.AppUserPrincipal;
import com.salonreview.config.CurrentBusinessContext;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Owner and manager read-only metrics; gated in SecurityConfig. */
@RestController
@RequestMapping("/api/metrics")
public class ManagerMetricsController {

    private final ManagerMetricsService service;
    private final CurrentBusinessContext business;
    private final ResponseOverviewService overview;
    private final NeedsReplyService needsReply;

    public ManagerMetricsController(ManagerMetricsService service, CurrentBusinessContext business, ResponseOverviewService overview,
                                    NeedsReplyService needsReply) {
        this.needsReply = needsReply;
        this.overview = overview;
        this.service = service;
        this.business = business;
    }

    @GetMapping("/response")
    public ManagerMetricsService.Leaderboard response(@RequestParam(defaultValue = "7d") String period) {
        return service.leaderboard(business.id(), period);
    }

    @GetMapping("/shift")
    public ManagerMetricsService.ShiftSummary shift(@AuthenticationPrincipal AppUserPrincipal me) {
        return service.shiftSummary(business.id(), me.getUserId(), me.getUsername());
    }

    @GetMapping("/needs-reply")
    public NeedsReplyService.Queue needsReply() {
        return needsReply.queue(business.id());
    }

    @GetMapping("/overview")
    public ResponseOverviewService.Overview overview(@AuthenticationPrincipal AppUserPrincipal me,
                                                     @RequestParam(defaultValue = "7d") String period) {
        return overview.overview(me.getUserId(), period);
    }
}
