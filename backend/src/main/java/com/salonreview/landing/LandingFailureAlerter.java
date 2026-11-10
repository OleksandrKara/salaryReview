package com.salonreview.landing;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import com.salonreview.telegram.TelegramNotificationService;

import jakarta.servlet.http.HttpServletRequest;

/** Tells staff in Telegram when a website booking or payment request fails on our side (5xx or an
 * unexpected exception). Card declines are alerted separately where the charge happens. The same
 * failure is sent at most once per five minutes, and nothing here ever throws. */
@Component
public class LandingFailureAlerter {

    static final String CONTACT_ATTRIBUTE = "landing.contact";
    private static final Logger log = LoggerFactory.getLogger(LandingFailureAlerter.class);
    private static final long WINDOW_MS = Duration.ofMinutes(5).toMillis();

    private final TelegramNotificationService telegram;
    private final LandingBusinessResolver resolver;
    private final Map<String, Long> lastSent = new ConcurrentHashMap<>();

    public LandingFailureAlerter(TelegramNotificationService telegram, LandingBusinessResolver resolver) {
        this.telegram = telegram;
        this.resolver = resolver;
    }

    static boolean watched(String path) {
        return path != null && (path.startsWith("/api/pmu/bookings/") || path.startsWith("/api/bookings"));
    }

    /** Masked contact line stored on the request so a failure alert can say who was booking. */
    static void rememberContact(String email, String phone) {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs == null) return;
        String mail = email == null || email.isBlank() ? "no email"
                : "***" + email.substring(Math.max(email.indexOf('@'), 0));
        String digits = phone == null ? "" : phone.replaceAll("\\D", "");
        String tel = digits.length() >= 4 ? "phone ending " + digits.substring(digits.length() - 4) : "no phone";
        attrs.setAttribute(CONTACT_ATTRIBUTE, mail + ", " + tel, RequestAttributes.SCOPE_REQUEST);
    }

    public void report(HttpServletRequest request, int status, String detail, String diagnostic, Throwable cause) {
        try {
            String path = request.getRequestURI();
            if (!watched(path)) return;
            String key = path + "|" + status + "|" + detail;
            long now = System.currentTimeMillis();
            if (lastSent.size() > 200) lastSent.values().removeIf(t -> now - t > WINDOW_MS);
            Long prev = lastSent.put(key, now);
            if (prev != null && now - prev < WINDOW_MS) {
                lastSent.put(key, prev);
                return;
            }
            long businessId = resolver.resolve(request.getHeader("Host")).id();
            Object contact = request.getAttribute(CONTACT_ATTRIBUTE);
            StringBuilder sb = new StringBuilder("⚠️ Website booking or payment failed on our side\n")
                    .append("Endpoint: ").append(request.getMethod()).append(' ').append(path).append('\n')
                    .append("HTTP status: ").append(status).append('\n')
                    .append("Error: ").append(detail).append('\n');
            if (diagnostic != null && !diagnostic.isBlank()) sb.append("Square: ").append(diagnostic).append('\n');
            if (cause != null) sb.append("Exception: ").append(cause.getClass().getSimpleName()).append('\n');
            if (contact != null) sb.append("Client: ").append(contact).append('\n');
            sb.append("Check the client's booking in Square and contact them if needed.");
            telegram.sendPlainText(businessId, sb.toString());
        } catch (RuntimeException e) {
            log.warn("Landing failure alert failed: {}", e.toString());
        }
    }
}
