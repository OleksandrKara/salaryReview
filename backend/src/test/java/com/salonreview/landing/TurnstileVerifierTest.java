package com.salonreview.landing;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

class TurnstileVerifierTest {

    private HttpServer server;
    private int status;
    private String body;
    private String lastRequestBody;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/verify", ex -> {
            lastRequestBody = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private TurnstileVerifier verifier(String secret) {
        return new TurnstileVerifier(secret, "http://127.0.0.1:" + server.getAddress().getPort() + "/verify");
    }

    @Test
    void unsetSecretFailsOpen() {
        assertThat(verifier("").verify(null, "1.2.3.4")).isTrue();
    }

    @Test
    void missingTokenFailsClosed() {
        assertThat(verifier("s").verify("", "1.2.3.4")).isFalse();
        assertThat(verifier("s").verify(null, "1.2.3.4")).isFalse();
    }

    @Test
    void siteverifyDecisionsAreHonoured() {
        status = 200;
        body = "{\"success\":true}";
        assertThat(verifier("s3cret").verify("tok", "1.2.3.4")).isTrue();
        assertThat(lastRequestBody).isEqualTo("secret=s3cret&response=tok&remoteip=1.2.3.4");
        body = "{\"success\":false,\"error-codes\":[\"invalid-input-response\"]}";
        assertThat(verifier("s3cret").verify("tok", "1.2.3.4")).isFalse();
    }

    @Test
    void serverErrorAndUnreachableFailOpen() {
        status = 503;
        body = "down";
        assertThat(verifier("s").verify("tok", "1.2.3.4")).isTrue();
        server.stop(0);
        assertThat(verifier("s").verify("tok", "1.2.3.4")).isTrue();
    }
}
