package com.salonreview.config;

import com.salonreview.domain.AppUser;
import com.salonreview.domain.Role;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class CurrentBusinessContextFilterTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static AppUser user() {
        return AppUser.builder().id(1L).username("owner1").passwordHash("h").role(Role.OWNER).active(true).build();
    }

    @Test
    @DisplayName("an authenticated AppUserPrincipal populates the context for the rest of the chain (what the controller sees)")
    void populatesFromAuthenticatedPrincipal() throws Exception {
        var principal = new AppUserPrincipal(user(), 7L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        var context = new CurrentBusinessContext();
        var filter = new CurrentBusinessContextFilter(context);
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse res = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        var seenDuringChain = new Long[1];
        doAnswer(inv -> { seenDuringChain[0] = context.id(); return null; }).when(chain).doFilter(req, res);

        filter.doFilter(req, res, chain);

        assertThat(seenDuringChain[0]).isEqualTo(7L); // the controller/rest of the chain saw it populated
        verify(chain).doFilter(req, res); // never short-circuits the chain
    }

    @Test
    @DisplayName("always clears the thread-local once the request finishes — never leaks into a later, unrelated request on a reused thread")
    void clearsAfterTheRequestFinishes() throws Exception {
        var principal = new AppUserPrincipal(user(), 7L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        var context = new CurrentBusinessContext();
        var filter = new CurrentBusinessContextFilter(context);
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse res = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(context.isPopulated()).isFalse();
    }

    @Test
    @DisplayName("clears even when the rest of the chain throws")
    void clearsEvenWhenChainThrows() throws Exception {
        var principal = new AppUserPrincipal(user(), 7L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        var context = new CurrentBusinessContext();
        var filter = new CurrentBusinessContextFilter(context);
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse res = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        doAnswer(inv -> { throw new RuntimeException("downstream failure"); }).when(chain).doFilter(req, res);

        try {
            filter.doFilter(req, res, chain);
        } catch (RuntimeException expected) {
            // propagates as normal — just confirming cleanup still ran
        }

        assertThat(context.isPopulated()).isFalse();
    }

    @Test
    @DisplayName("an unauthenticated request leaves the context unpopulated, not defaulted to any business")
    void noOpsWhenUnauthenticated() throws Exception {
        var context = new CurrentBusinessContext();
        var filter = new CurrentBusinessContextFilter(context);
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse res = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(context.isPopulated()).isFalse();
        verify(chain).doFilter(req, res);
    }
}
