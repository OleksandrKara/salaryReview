package com.salonreview.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

/**
 * Phase-2 auth: real per-user accounts ({@code app_user}) with roles, over a server-side session.
 * Login is {@code POST /api/login} (form-encoded username/password); on success the session cookie
 * (JSESSIONID) identifies the caller. The browser never holds this cookie — the Next.js server proxy
 * does, and forwards it. Authorities are {@code ROLE_OWNER/MANAGER/PROVIDER}; fine-grained checks use
 * {@code @PreAuthorize} on the self/feedback controllers.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final ObjectMapper json = new ObjectMapper();

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, CurrentBusinessContext currentBusinessContext)
            throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                // Populates CurrentBusinessContext right after Spring Security resolves the
                // Authentication (session-restored or freshly logged-in) — see design.md D7 and
                // CurrentBusinessContextFilter's own doc comment.
                .addFilterAfter(new CurrentBusinessContextFilter(currentBusinessContext),
                        UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/info", "/api/login").permitAll()
                        // Spring Boot's internal error-rendering path: when a controller throws
                        // (ResponseStatusException or otherwise), the container performs an internal
                        // forward to /error to render the response body, which re-enters this whole
                        // filter chain as a *second* pass over the same request. By that point the
                        // original pass's SecurityContext has already been cleared (each context-bearing
                        // filter clears it in its own finally block once the first pass unwinds), so
                        // without this the forwarded pass looks anonymous, AuthorizationFilter rejects it
                        // under the anyRequest().authenticated() catch-all below, and the resulting
                        // AuthenticationException gets translated by the entry point into a bare 401 that
                        // silently overwrites whatever status/body the controller actually intended (e.g.
                        // a 409 conflict coming back as an empty 401). Permitting /error doesn't weaken
                        // anything: the authorization decision for the real endpoint was already made on
                        // the first pass, before the exception was even thrown.
                        .requestMatchers("/error").permitAll()
                        // Service-to-service calls from mani/akluxnails-home (Telegram 4-hand-request
                        // relay) — no user session exists for these callers; auth is enforced by the
                        // controller's own X-Internal-Api-Key check instead (see
                        // InternalNotificationController).
                        .requestMatchers("/api/internal/**").permitAll()
                        // Square's payment webhook, Twilio's inbound-SMS and delivery-status webhooks, and
                        // the click-tracked short-link redirect are all called by third parties with no
                        // session — each enforces its own signature check (or, for /r/**, is a harmless
                        // public redirect with nothing sensitive to protect). See
                        // openspec/changes/sms-automations-hub.
                        // Twilio's inbound-Voice webhook is the same "harmless public endpoint" shape —
                        // a fixed <Dial> response regardless of caller, nothing to protect (see
                        // openspec/changes/lead-followup-and-manager-inbox tasks.md section 6).
                        .requestMatchers("/api/public/webhooks/square", "/api/public/sms/inbound",
                                "/api/public/sms/status", "/api/public/voice/inbound", "/r/**")
                                .permitAll()
                        // MMS photo serving — same "harmless public endpoint" shape as /r/**, keyed by an
                        // opaque, unguessable token rather than a session, so both the dashboard's <img>
                        // tags and Twilio's own outbound-media-fetch requests can retrieve a file with no
                        // auth header (see SmsMediaController).
                        .requestMatchers("/api/public/sms-media/**").permitAll()
                        // Retention is read-only visibility for managers too (same data as owners, no
                        // other owner routes). Listed first so it wins over the owner-only catch-all.
                        .requestMatchers(HttpMethod.GET, "/api/owner/retention", "/api/owner/retention/**")
                                .hasAnyRole("OWNER", "MANAGER")
                        // ADS_MANAGER: read-only visibility into marketing only — dashboard, contacts,
                        // analytics, abuse blocks. Listed first (GET-only) so it wins over the owner-only
                        // catch-all below; every write under /api/owner/marketing/** (variant rename/
                        // active/description, delete, duplicate, stats-since) still falls through to that
                        // catch-all and stays OWNER-only — except ad spend just below, the one deliberate
                        // write this role is allowed (entering the month's ad budget is what Ads Manager
                        // is there to do, not a hole in its read-only scope).
                        .requestMatchers(HttpMethod.GET, "/api/owner/marketing", "/api/owner/marketing/**")
                                .hasAnyRole("OWNER", "ADS_MANAGER")
                        .requestMatchers(HttpMethod.POST, "/api/owner/marketing/ads-report/spend")
                                .hasAnyRole("OWNER", "ADS_MANAGER")
                        // SMS automations hub: MANAGER gets read/reply access to the activity/
                        // conversation view (openspec/changes/lead-followup-and-manager-inbox
                        // design.md D6), but toggling an automation on/off
                        // (PUT /api/owner/automations/{key}) is still an OWNER-only business
                        // decision — that request falls through to the catch-all below, unaffected
                        // by this matcher. Listed first so it wins.
                        .requestMatchers("/api/owner/automations/activity/**").hasAnyRole("OWNER", "MANAGER")
                        // Business creation (Phase 5.1) — pragmatically OWNER-gated like everything
                        // else here rather than a separate platform_admin role, since there is
                        // exactly one owner account in practice today; see design.md D4/5.2 for the
                        // real platform_admin table this should move to once that's needed.
                        .requestMatchers("/api/users/**", "/api/owner/**", "/api/rag/admin/**", "/api/platform/**")
                                .hasRole("OWNER")
                        .requestMatchers("/api/settlements/me/**").hasRole("PROVIDER")
                        // A provider/manager's own read-only "My Documents" — list + download only,
                        // never create/edit/delete (that stays under /api/owner/** above, OWNER-only).
                        .requestMatchers("/api/staff-documents/me/**").hasAnyRole("PROVIDER", "MANAGER")
                        // RAG admin (upload/approve/delete/config) is OWNER-only above; asking +
                        // feedback are OWNER+MANAGER. The admin matcher is listed first so it wins.
                        .requestMatchers("/api/rag/**").hasAnyRole("OWNER", "MANAGER")
                        // KB articles: any authenticated role may read (per-article visibility is
                        // enforced in the service via visible_roles); only OWNER+MANAGER may write,
                        // sync, or use AI drafting. The GET matcher is listed first so it wins.
                        .requestMatchers(HttpMethod.GET, "/api/kb-articles/download-all").hasRole("OWNER")
                        .requestMatchers(HttpMethod.GET, "/api/kb-articles/*/download").hasRole("OWNER")
                        .requestMatchers(HttpMethod.GET, "/api/kb-articles/**").authenticated()
                        .requestMatchers("/api/kb-articles/**").hasAnyRole("OWNER", "MANAGER")
                        // SOPs: owner authors/publishes/archives + views the roster, version history,
                        // and RAG sync; managers/providers read (audience-filtered in the service) and
                        // acknowledge. Specific matchers first; the catch-all is OWNER-only.
                        .requestMatchers(HttpMethod.GET, "/api/sops/rag-sync").hasRole("OWNER")
                        .requestMatchers(HttpMethod.GET, "/api/sops/download-all").hasRole("OWNER")
                        .requestMatchers(HttpMethod.GET, "/api/sops/*/download").hasRole("OWNER")
                        .requestMatchers(HttpMethod.GET, "/api/sops/*/acknowledgment-status").hasRole("OWNER")
                        .requestMatchers(HttpMethod.GET, "/api/sops/*/versions").hasRole("OWNER")
                        .requestMatchers(HttpMethod.POST, "/api/sops/*/acknowledge").hasAnyRole("MANAGER", "PROVIDER")
                        .requestMatchers(HttpMethod.GET, "/api/sops/**").authenticated()
                        .requestMatchers("/api/sops/**").hasRole("OWNER")
                        // Redos are a manager's only management task (owner + manager); the providers
                        // list is needed to record one.
                        // Everything else below — salary settlements, Square sync, prepaid, owner comps,
                        // no-show fees, suspicious review — is owner-only (managers don't manage salaries).
                        // Manager time tracking: the payroll view + rate-setting are owner-only; the
                        // self endpoints (clock in/out, own entries) are for managers (owner allowed too,
                        // though owners don't clock time). Specific /admin matcher first so it wins.
                        .requestMatchers("/api/time/admin/**").hasRole("OWNER")
                        .requestMatchers("/api/time/**").hasAnyRole("OWNER", "MANAGER")
                        // Manual adjustments: managers get read-only visibility (the routine payroll
                        // bookkeeping context they already see via redos/time), but adding or deleting one
                        // is owner-only — unlike redos below. GET matcher listed first so it wins.
                        .requestMatchers(HttpMethod.GET, "/api/manual-adjustments", "/api/manual-adjustments/**")
                                .hasAnyRole("OWNER", "MANAGER")
                        .requestMatchers("/api/manual-adjustments", "/api/manual-adjustments/**").hasRole("OWNER")
                        .requestMatchers("/api/redos/**", "/api/providers/**")
                                .hasAnyRole("OWNER", "MANAGER")
                        .requestMatchers("/api/settlements/**", "/api/square/**", "/api/pay-periods/**",
                                "/api/prepaid/**", "/api/owner-customers/**",
                                "/api/no-show-fees/**", "/api/suspicious/**", "/api/cancellations/**")
                                .hasRole("OWNER")
                        .anyRequest().authenticated())
                .formLogin(form -> form
                        .loginProcessingUrl("/api/login")
                        .successHandler((req, res, authn) -> writeMe(res, authn.getPrincipal()))
                        .failureHandler((req, res, ex) -> res.sendError(HttpServletResponse.SC_UNAUTHORIZED)))
                .logout(out -> out
                        .logoutUrl("/api/logout")
                        .deleteCookies("JSESSIONID")
                        .logoutSuccessHandler((req, res, authn) -> res.setStatus(HttpServletResponse.SC_OK)))
                // API clients want a 401, not a redirect to a login form, when unauthenticated.
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
        return http.build();
    }

    /** Serialize the authenticated principal as {@code {username, role, providerId}} (also used by /api/me). */
    private void writeMe(HttpServletResponse res, Object principal) throws java.io.IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        if (principal instanceof AppUserPrincipal p) {
            body.put("username", p.getUsername());
            body.put("role", p.getRole().name());
            body.put("providerId", p.getProviderId());
        }
        res.setStatus(HttpServletResponse.SC_OK);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(res.getWriter(), body);
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
