package com.salonreview.web;

import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.domain.ProviderScheduleClosureAlertConfig;
import com.salonreview.sms.ProviderScheduleClosureAlertConfigService;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.Instant;

/**
 * Owner-editable notice-threshold (hours) for the {@code provider_schedule_closure_alert}
 * Telegram alert — falls under the existing {@code /api/owner/**} matcher in {@link
 * com.salonreview.config.SecurityConfig}, no new security config needed. Invalid values (see
 * {@link ProviderScheduleClosureAlertConfigService#update}) surface as a 400 via {@code
 * GlobalExceptionHandler}'s {@code IllegalArgumentException} mapping, same convention as {@code
 * SmsAutomationService#setEnabled}.
 */
@RestController
@RequestMapping("/api/owner/settings/provider-schedule-closure-alert")
public class ProviderScheduleClosureAlertSettingsController {

    private final ProviderScheduleClosureAlertConfigService configService;
    private final CurrentBusinessContext currentBusinessContext;

    public ProviderScheduleClosureAlertSettingsController(ProviderScheduleClosureAlertConfigService configService,
                                                           CurrentBusinessContext currentBusinessContext) {
        this.configService = configService;
        this.currentBusinessContext = currentBusinessContext;
    }

    public record SettingsDto(int noticeThresholdHours, int minimumLossWindowMinutes, boolean observationOnly,
                              Instant updatedAt, String updatedBy) {
    }

    public record UpdateRequest(int noticeThresholdHours, Integer minimumLossWindowMinutes, Boolean observationOnly) {
    }

    @GetMapping
    public SettingsDto get() {
        var settings = configService.getSettings(currentBusinessContext.id());
        return new SettingsDto(settings.noticeThresholdHours(), settings.minimumLossWindowMinutes(),
                settings.observationOnly(), settings.updatedAt(), settings.updatedBy());
    }

    @PutMapping
    public SettingsDto update(@RequestBody UpdateRequest body, Principal principal) {
        Long businessId = currentBusinessContext.id();
        ProviderScheduleClosureAlertConfig config =
                body.minimumLossWindowMinutes() == null && body.observationOnly() == null
                        ? configService.update(businessId, body.noticeThresholdHours(), principal.getName())
                        : configService.update(businessId, body.noticeThresholdHours(), body.minimumLossWindowMinutes(),
                                body.observationOnly(), principal.getName());
        return new SettingsDto(config.getNoticeThresholdHours(), config.getMinimumLossWindowMinutes(),
                config.isObservationOnly(), config.getUpdatedAt(), config.getUpdatedBy());
    }
}
