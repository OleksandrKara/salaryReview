package com.salonreview.sms;

import com.salonreview.repo.SmsMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Instant reply to the website's stock "Hi! I have a question about ..." text when the visitor
 * sent it as-is (owner request 2026-11-22). Someone who edits the text has asked something real
 * and gets a human; someone who just taps Send has told us nothing, so the reply asks which
 * service and when, and the first human answer can already be about booking.
 *
 * <p>Sends only when the whole message is that one stock sentence, the number has never been
 * texted by us before (so a customer, an open lead funnel or an earlier thread never gets it),
 * and the {@value #AUTOMATION_KEY} automation is switched on for the business. Never throws:
 * the inbound webhook must finish logging and alerting staff whatever happens here.
 *
 * <p>The reply goes out {@value #MIN_DELAY_SECONDS} to {@value #MAX_DELAY_SECONDS} seconds after
 * the text arrives (owner 2026-11-22: an instant reply reads as a bot, about half a minute reads as
 * someone with the phone in hand). Everything is checked again at send time, so a manager who
 * answers in that window, or switches the automation off, stops it. The wait is in memory: a
 * restart inside the window drops that one reply, and staff were alerted anyway.
 */
@Service
public class FirstContactAutoReplyService {

    public static final String AUTOMATION_KEY = "first_contact_autoreply";

    private static final Logger log = LoggerFactory.getLogger(FirstContactAutoReplyService.class);

    /** One sentence, "Hi! I have a question about|for X." with a closing period and nothing
     * after it: the form every site button prefills. A period inside a word (AK.LUX.NAILS) is fine; any
     * added sentence, question mark or "Thanks" breaks the match, which is the point. */
    private static final Pattern STOCK_TEXT = Pattern.compile(
            "^hi!?\\s+i have a question (?:about|for) ((?:[^.?!\\n]|\\.(?=\\S)){3,100})\\.$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ACADEMY = Pattern.compile("academy|course|training|class|program", Pattern.CASE_INSENSITIVE);
    private static final Pattern NAILS = Pattern.compile("nail|ak\\.lux|manicure|pedicure", Pattern.CASE_INSENSITIVE);
    private static final Pattern PMU = Pattern.compile(
            "permanent|makeup|pmu|brow|lip|eye|lash|scalp|smp|areola|tattoo|microblad|ombre|powder", Pattern.CASE_INSENSITIVE);

    static final int MIN_DELAY_SECONDS = 30;
    static final int MAX_DELAY_SECONDS = 45;

    private final TwilioSmsService smsService;
    private final SmsAutomationService automationService;
    private final SmsMessageRepository messages;
    private final ScheduledExecutorService scheduler;

    @org.springframework.beans.factory.annotation.Autowired
    public FirstContactAutoReplyService(TwilioSmsService smsService, SmsAutomationService automationService,
                                        SmsMessageRepository messages) {
        this(smsService, automationService, messages, Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "first-contact-autoreply");
            t.setDaemon(true);
            return t;
        }));
    }

    FirstContactAutoReplyService(TwilioSmsService smsService, SmsAutomationService automationService,
                                 SmsMessageRepository messages, ScheduledExecutorService scheduler) {
        this.smsService = smsService;
        this.automationService = automationService;
        this.messages = messages;
        this.scheduler = scheduler;
    }

    /** The template to answer {@code body} with, or empty when it is not the untouched stock text. */
    static Optional<String> templateKeyFor(String body) {
        if (body == null) return Optional.empty();
        var m = STOCK_TEXT.matcher(body.trim().replaceAll("\\s+", " "));
        if (!m.matches()) return Optional.empty();
        String subject = m.group(1);
        if (ACADEMY.matcher(subject).find()) return Optional.of("first_contact_autoreply_academy");
        if (NAILS.matcher(subject).find()) return Optional.of("first_contact_autoreply_nails");
        if (PMU.matcher(subject).find()) return Optional.of("first_contact_autoreply_pmu");
        return Optional.empty();
    }

    /** Schedules the reply if the text qualifies; returns whether one was scheduled. */
    public boolean maybeReply(Long businessId, String from, String body) {
        try {
            Optional<String> templateKey = templateKeyFor(body);
            if (templateKey.isEmpty() || !shouldSend(businessId, from)) return false;
            long delay = ThreadLocalRandom.current().nextInt(MIN_DELAY_SECONDS, MAX_DELAY_SECONDS + 1);
            scheduler.schedule(() -> send(businessId, templateKey.get(), from), delay, TimeUnit.SECONDS);
            return true;
        } catch (RuntimeException e) {
            log.warn("First-contact auto-reply could not be scheduled, staff still alerted: {}", e.toString());
            return false;
        }
    }

    /** Checked when the text arrives and again when the delay is over. Not inside sendTemplated
     * alone, so a switched-off automation leaves no "automation_disabled" row per stock text. */
    private boolean shouldSend(Long businessId, String from) {
        return automationService.isEnabled(businessId, AUTOMATION_KEY)
                && !messages.existsByBusinessIdAndPhoneNumberAndDirectionAndStatus(businessId, from, "OUTBOUND", "SENT");
    }

    private void send(Long businessId, String templateKey, String from) {
        try {
            if (!shouldSend(businessId, from)) return;
            if (smsService.sendTemplated(businessId, templateKey, from, Map.of()).sent()) {
                log.info("First-contact auto-reply sent ({}) for business {}", templateKey, businessId);
            }
        } catch (RuntimeException e) {
            log.warn("First-contact auto-reply failed, staff still alerted: {}", e.toString());
        }
    }
}
