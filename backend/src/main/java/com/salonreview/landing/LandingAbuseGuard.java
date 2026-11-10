package com.salonreview.landing;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Guards the real submission endpoints: honeypot, per-phone and per-IP limits, then Turnstile. */
@Component
public class LandingAbuseGuard {

    private static final Logger log = LoggerFactory.getLogger(LandingAbuseGuard.class);
    static final int MAX_PER_PHONE_PER_DAY = 3;
    static final int MAX_PER_IP_PER_HOUR = 5;
    private static final List<String> RATE_LIMITED_TYPES = List.of("booking", "four_hand_request");
    private static final Pattern TEST_PHONE = Pattern.compile("^\\+1\\d{3}55501\\d{2}$");
    private static final String STUDIO_PHONE = "(619) 323-1185";
    private static final Map<String, String> MESSAGES = Map.of(
            "rate_limit_phone", "This phone number has reached today's limit of 3 online bookings. To book or change an "
                    + "appointment, please call or text us at " + STUDIO_PHONE + ".",
            "rate_limit_ip", "Too many bookings from this network in the last hour. Please try again later, or call or "
                    + "text us at " + STUDIO_PHONE + ".",
            "turnstile_failed", "We couldn't verify the security check. Please refresh the page and try again "
                    + "(or call/text us at " + STUDIO_PHONE + ").");
    private static final String GENERIC = "We couldn't verify your submission. Please try again.";

    private final LandingWriteRepository repository;
    private final TurnstileVerifier turnstile;

    public LandingAbuseGuard(LandingWriteRepository repository, TurnstileVerifier turnstile) {
        this.repository = repository;
        this.turnstile = turnstile;
    }

    static boolean isTestPhone(String phone) {
        return phone != null && !phone.isEmpty() && TEST_PHONE.matcher(phone).find();
    }

    /** Throws LandingException(400, message) on the first failed check, after logging it. */
    public void check(long businessId, String endpoint, String phone, String ip, String honeypot, String turnstileToken) {
        if (honeypot != null && !honeypot.isEmpty()) reject(businessId, endpoint, "honeypot", phone, ip);

        boolean testing = isTestPhone(phone);
        if (phone != null && !phone.isEmpty() && !testing) {
            Instant since = Instant.now().minus(Duration.ofDays(1));
            if (repository.countRecentSubmissionsByPhone(phone, RATE_LIMITED_TYPES, since) >= MAX_PER_PHONE_PER_DAY) {
                reject(businessId, endpoint, "rate_limit_phone", phone, ip);
            }
        }
        if (ip != null && !ip.isEmpty() && !testing) {
            Instant since = Instant.now().minus(Duration.ofHours(1));
            if (repository.countRecentSubmissionsByIp(ip, RATE_LIMITED_TYPES, since) >= MAX_PER_IP_PER_HOUR) {
                reject(businessId, endpoint, "rate_limit_ip", phone, ip);
            }
        }
        if (!turnstile.verify(turnstileToken, ip)) reject(businessId, endpoint, "turnstile_failed", phone, ip);
    }

    private void reject(long businessId, String endpoint, String reason, String phone, String ip) {
        try {
            repository.insertAbuseBlock(businessId, endpoint, reason, phone, ip);
        } catch (RuntimeException e) {
            log.error("Failed to log abuse block (rejection still enforced)", e);
        }
        log.warn("Rejected {} submission: reason={} phone={} ip={}", endpoint, reason, phone, ip);
        throw new LandingException(400, MESSAGES.getOrDefault(reason, GENERIC));
    }
}
