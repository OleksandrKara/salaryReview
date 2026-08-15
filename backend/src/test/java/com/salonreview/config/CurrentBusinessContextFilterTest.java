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
    @DisplayName("an authenticated AppUserPrincipal populates the context before the request continues")
    void populatesFromAuthenticatedPrincipal() throws Exception {
        var principal = new AppUserPrincipal(user(), 7L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        var context = new CurrentBusinessContext();
        var filter = new CurrentBusinessContextFilter(context);
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse res = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(context.id()).isEqualTo(7L);
        verify(chain).doFilter(req, res); // never short-circuits the chain
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
