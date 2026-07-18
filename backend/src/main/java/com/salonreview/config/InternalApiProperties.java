package com.salonreview.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Shared secret for service-to-service calls from mani/akluxnails-home (Telegram 4-hand-request
 * relay — see {@link com.salonreview.web.InternalNotificationController}). Blank means every
 * internal call is rejected — there's no sensible "open" default for this.
 */
@Component
@ConfigurationProperties(prefix = "internal.api")
@Getter
@Setter
public class InternalApiProperties {

    private String key = "";
}
