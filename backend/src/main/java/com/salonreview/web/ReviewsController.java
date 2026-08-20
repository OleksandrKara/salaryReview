package com.salonreview.web;

import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.sms.CheckoutReviewInsightsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The {@code /owner/reviews} dashboard's data: every checkout-review-request reply, grouped by
 * provider, with an overall and per-provider average rating — see V120 and {@link
 * CheckoutReviewInsightsService}. Falls under {@code /api/owner/**} in {@link
 * com.salonreview.config.SecurityConfig}, no new security config needed.
 */
@RestController
@RequestMapping("/api/owner/reviews")
public class ReviewsController {

    private final CheckoutReviewInsightsService insights;
    private final CurrentBusinessContext currentBusinessContext;

    public ReviewsController(CheckoutReviewInsightsService insights, CurrentBusinessContext currentBusinessContext) {
        this.insights = insights;
        this.currentBusinessContext = currentBusinessContext;
    }

    @GetMapping
    public CheckoutReviewInsightsService.Overview overview() {
        return insights.overview(currentBusinessContext.id());
    }
}
