package com.salonreview.sms;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Live-update fan-out for the manager conversation view — see SmsEventBroadcaster's own doc for why
 * this is a bare "something changed" signal rather than a full payload.
 */
class SmsEventBroadcasterTest {

    private final SmsEventBroadcaster broadcaster = new SmsEventBroadcaster();

    @Test
    @DisplayName("subscribe returns a distinct emitter per call")
    void subscribeReturnsDistinctEmitters() {
        SseEmitter first = broadcaster.subscribe();
        SseEmitter second = broadcaster.subscribe();

        assertThat(first).isNotNull().isNotSameAs(second);
    }

    @Test
    @DisplayName("broadcast with no subscribers is a harmless no-op")
    void broadcastWithNoSubscribersDoesNotThrow() {
        assertThatCode(() -> broadcaster.broadcast("+15551234567")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("broadcast to a subscribed emitter does not throw")
    void broadcastToSubscribedEmitterDoesNotThrow() {
        broadcaster.subscribe();

        assertThatCode(() -> broadcaster.broadcast("+15551234567")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("heartbeat with a subscribed emitter does not throw")
    void heartbeatDoesNotThrow() {
        broadcaster.subscribe();

        assertThatCode(broadcaster::heartbeat).doesNotThrowAnyException();
    }
}
