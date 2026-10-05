package com.salonreview.sms;

import com.salonreview.domain.TwilioSmsConfig;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TwilioSmsClientMediaTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    @DisplayName("fetchMedia follows Twilio's 307 to the signed CDN link, without sending our credentials there")
    void followsRedirectWithoutAuth() throws Exception {
        AtomicReference<String> cdnAuth = new AtomicReference<>("unset");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/media", ex -> {
            assertThat(ex.getRequestHeaders().getFirst("Authorization")).startsWith("Basic ");
            ex.getResponseHeaders().add("Location", "/cdn/file?Signature=abc");
            ex.sendResponseHeaders(307, -1);
            ex.close();
        });
        server.createContext("/cdn/file", ex -> {
            cdnAuth.set(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
            byte[] body = "JPEGDATA".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();

        TwilioSmsConfig config = new TwilioSmsConfig();
        config.setApiKey("SKkey");
        config.setApiSecret("secret");
        byte[] data = new TwilioSmsClient("http://localhost").fetchMedia(config,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/media");

        assertThat(new String(data, StandardCharsets.UTF_8)).isEqualTo("JPEGDATA");
        assertThat(cdnAuth.get()).isEqualTo("null");
    }
}
