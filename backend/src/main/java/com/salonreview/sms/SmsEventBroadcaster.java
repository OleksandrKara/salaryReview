package com.salonreview.sms;

import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Live-update fan-out for the manager conversation view ({@code /admin/messages}) — lets the page
 * refresh itself the instant a message/read-state/block-state changes, instead of the manager
 * having to reload the tab. See {@link SmsMessageLogService}, the single choke point every message
 * write already goes through, for where {@link #broadcast} is actually called.
 *
 * <p>Deliberately a "something changed for this phone number" signal, not a payload carrying the
 * full new state — the frontend already has well-tested refetch logic (conversations list +
 * open-thread reload) for every one of these cases, so reusing it here is far less code and far
 * fewer edge cases than reconstructing DTOs at every call site and merging them into client state.
 *
 * <p>No message broker needed: at this app's scale (a couple of staff SSE connections at once), an
 * in-memory emitter list on a single backend instance is simplest and sufficient.
 */
@Component
public class SmsEventBroadcaster {

    /** Comfortably under nginx's default 60s proxy_read_timeout — an idle SSE connection (no real
     * events for a while, since customer texts arrive sporadically) would otherwise risk being
     * silently dropped by the reverse proxy. */
    private static final long HEARTBEAT_INTERVAL_MS = 20_000;

    /** No fixed timeout — the connection lives until the browser tab closes or the network drops,
     * at which point send() below throws and this emitter is pruned. EventSource itself already
     * auto-reconnects on any drop, so there's no user-visible gap either way. */
    private static final long EMITTER_TIMEOUT_MS = 0L;

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        return emitter;
    }

    /** Tells every connected manager/owner tab that this phone number's conversation changed — a
     * new message, a read/unread flip, or a block/unblock. The frontend decides what to refetch. */
    public void broadcast(String phoneNumber) {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("update").data(Map.of("phoneNumber", phoneNumber), MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException e) {
                emitters.remove(emitter);
            }
        }
    }

    @Scheduled(fixedRate = HEARTBEAT_INTERVAL_MS)
    void heartbeat() {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().comment("ping"));
            } catch (IOException | IllegalStateException e) {
                emitters.remove(emitter);
            }
        }
    }
}
