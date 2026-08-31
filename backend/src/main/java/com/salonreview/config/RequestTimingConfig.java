package com.salonreview.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Registers {@link RequestTimingFilter} at the very front of the servlet filter chain — {@link
 * Ordered#HIGHEST_PRECEDENCE} puts it before Spring Security's own filter chain, so the timing
 * covers the whole request (auth included), not just what happens after a 401/403 would already
 * have short-circuited it.
 */
@Configuration
public class RequestTimingConfig {

    @Bean
    public FilterRegistrationBean<RequestTimingFilter> requestTimingFilter() {
        FilterRegistrationBean<RequestTimingFilter> registration = new FilterRegistrationBean<>(new RequestTimingFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
