package com.salonreview.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Times every HTTP request end to end (registered at {@link org.springframework.core.Ordered#HIGHEST_PRECEDENCE}
 * — see {@link RequestTimingConfig} — so it wraps the whole servlet filter chain, Spring Security
 * included, not just controller dispatch). One filter, applied automatically to every endpoint —
 * no per-service {@code log.info("... took Xms")} calls to remember to add, and no risk of missing
 * a slow endpoint nobody thought to instrument.
 *
 * <p>Anything at or past {@link #SLOW_MS} logs a grep-able "SLOW REQUEST" line at WARN — {@code
 * docker logs salonreview-backend-* | grep "SLOW REQUEST"} finds every one across however far back
 * the container's log retention goes. Everything else logs at DEBUG (silent by default; the root
 * logger here is INFO — see application.yml), so this costs nothing in normal operation but can be
 * turned up for full request-timing visibility by setting {@code logging.level.com.salonreview.config.RequestTimingFilter=DEBUG}.
 *
 * <p>Deliberately doesn't try to resolve {@link CurrentBusinessContext} — that's set by a *later*
 * filter in Spring Security's own chain ({@code CurrentBusinessContextFilter}, added via {@code
 * addFilterAfter}), and this filter runs before all of that by design (to time the full request,
 * auth included). The request path/query string already identifies which page/slug a slow request
 * belongs to for every owner-marketing endpoint, which is what actually matters for finding a slow
 * page load.
 */
public class RequestTimingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestTimingFilter.class);
    private static final long SLOW_MS = 1500;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long startedAtNanos = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long elapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000;
            String query = request.getQueryString();
            String path = query != null ? request.getRequestURI() + "?" + query : request.getRequestURI();
            if (elapsedMs >= SLOW_MS) {
                log.warn("SLOW REQUEST: {} {} took {}ms (status={})",
                        request.getMethod(), path, elapsedMs, response.getStatus());
            } else {
                log.debug("{} {} took {}ms (status={})", request.getMethod(), path, elapsedMs, response.getStatus());
            }
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.equals("/actuator/health") || path.equals("/favicon.ico");
    }
}
