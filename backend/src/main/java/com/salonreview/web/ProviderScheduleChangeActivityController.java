package com.salonreview.web;

import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.sms.ProviderScheduleChangeStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Existing activity matcher permits OWNER/MANAGER. No caller-supplied tenant or global row ID. */
@RestController
@RequestMapping("/api/owner/automations/activity/provider-schedule-changes")
public class ProviderScheduleChangeActivityController {
    private final CurrentBusinessContext context;
    private final ProviderScheduleChangeStore store;

    public ProviderScheduleChangeActivityController(CurrentBusinessContext context, ProviderScheduleChangeStore store) {
        this.context = context;
        this.store = store;
    }

    @GetMapping
    public ProviderScheduleChangeStore.History history() { return store.history(context.id()); }
}
