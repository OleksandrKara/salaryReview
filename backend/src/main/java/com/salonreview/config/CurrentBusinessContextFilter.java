package com.salonreview.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Populates {@link CurrentBusinessContext} from the authenticated {@link AppUserPrincipal}, once per
 * request, before controller dispatch — see design.md D7. Runs after Spring Security has resolved
 * the {@link Authentication} (registered via {@code .addFilterAfter(..., UsernamePasswordAuthenticationFilter.class)}
 * in {@link SecurityConfig}), so this sees a populated {@code SecurityContextHolder} both for the
 * login request itself and for every subsequent session-cookie-authenticated request (restored
 * earlier in the chain by Spring Security's own session filter). Silently no-ops for unauthenticated
 * requests (public webhooks, health checks, /api/login) — nothing on those paths reads
 * {@link CurrentBusinessContext#id()}, and every business-scoped route already requires
 * authentication (see SecurityConfig's {@code anyRequest().authenticated()} catch-all).
 */
public class CurrentBusinessContextFilter extends OncePerRequestFilter {

    private final CurrentBusinessContext context;

    public CurrentBusinessContextFilter(CurrentBusinessContext context) {
        this.context = context;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AppUserPrincipal p && p.getActiveBusinessId() != null) {
            context.set(p.getActiveBusinessId());
        }
        chain.doFilter(request, response);
    }
}
