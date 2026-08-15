package com.salonreview.config;

import com.salonreview.domain.AppUser;
import com.salonreview.domain.Business;
import com.salonreview.domain.BusinessMembership;
import com.salonreview.domain.Role;
import com.salonreview.repo.AppUserRepository;
import com.salonreview.repo.BusinessMembershipRepository;
import com.salonreview.repo.BusinessRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for a real production bug: a controller throwing {@code ResponseStatusException}
 * (e.g. {@code UserController.create()}'s duplicate-username check) came back as an empty HTTP 401
 * instead of the intended status. Root cause: {@code response.sendError()} triggers a container-level
 * internal forward to {@code /error}, which re-enters the *entire* Spring Security filter chain as a
 * second pass over the same request — by which point the first pass's {@code SecurityContext} has
 * already been cleared (each context-bearing filter, including our own
 * {@link CurrentBusinessContextFilter}, clears it in a {@code finally} block once its own
 * {@code chain.doFilter()} call returns). The forwarded pass then looks anonymous,
 * {@code anyRequest().authenticated()} rejects it, and the resulting {@code AuthenticationException}
 * gets translated into a bare 401 that silently overwrites the controller's real response.
 *
 * <p>This can only be observed against the *real* filter chain — a standalone {@code MockMvc} setup
 * (the pattern most controller tests in this repo use) never exercises container-level error dispatch
 * at all, so it can't catch this. Uses the JDK's own {@code HttpClient} against the embedded server
 * (no new test dependency) so cookie-based session auth behaves exactly as it does for a real browser/
 * proxy request. Seeds its own OWNER account directly rather than relying on {@code OwnerBootstrap} —
 * that only seeds when {@code app_user} is empty, which doesn't hold in CI where every
 * {@code @SpringBootTest} class shares one real Postgres instance across the whole suite run, so by
 * the time this class's context boots other tests have almost always already created rows. Needs a
 * real Postgres to boot the full application context (fails locally without one, passes in CI — same
 * as {@code BusinessRepositoryTest}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SecurityConfigErrorDispatchTest {

    private static final String USERNAME = "errdispatchowner";
    private static final String PASSWORD = "errDispatchTestPw1";

    @LocalServerPort
    private int port;

    @Autowired
    private AppUserRepository appUsers;
    @Autowired
    private BusinessRepository businesses;
    @Autowired
    private BusinessMembershipRepository memberships;
    @Autowired
    private PasswordEncoder encoder;

    private final HttpClient http = HttpClient.newBuilder()
            .cookieHandler(new java.net.CookieManager())
            .build();

    @Test
    void controllerThrownResponseStatusExceptionSurvivesTheErrorDispatch() throws Exception {
        seedOwner();
        login();

        String username = "err-dispatch-dupe-" + System.nanoTime();
        HttpResponse<String> first = createUser(username);
        assertThat(first.statusCode()).isEqualTo(200);

        // The duplicate-username check throws ResponseStatusException(CONFLICT) — before the /error
        // fix, this came back as an empty 401 instead of the real status.
        HttpResponse<String> duplicate = createUser(username);
        assertThat(duplicate.statusCode()).isEqualTo(409);
        assertThat(duplicate.body()).contains("\"status\":409");
    }

    private void seedOwner() {
        if (appUsers.findByUsername(USERNAME).isPresent()) return;
        Business businessA = businesses.findByShortCode("akluxnails").orElseThrow();
        AppUser owner = appUsers.save(AppUser.builder()
                .businessId(businessA.getId())
                .username(USERNAME)
                .passwordHash(encoder.encode(PASSWORD))
                .role(Role.OWNER)
                .active(true)
                .build());
        memberships.save(BusinessMembership.builder()
                .businessId(businessA.getId()).userId(owner.getId()).role(Role.OWNER).build());
    }

    private void login() throws Exception {
        String form = "username=" + USERNAME + "&password=" + PASSWORD;
        HttpRequest req = HttpRequest.newBuilder(URI.create(url("/api/login")))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).isEqualTo(200);
    }

    private HttpResponse<String> createUser(String username) throws Exception {
        String body = """
                {"username":"%s","password":"x12345678","role":"MANAGER"}
                """.formatted(username);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url("/api/users")))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
