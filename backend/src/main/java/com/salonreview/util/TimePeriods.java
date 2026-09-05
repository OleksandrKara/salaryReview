package com.salonreview.util;

import java.time.LocalDate;
import java.time.Period;

/** Human-readable elapsed time between two dates, e.g. "1 year and 7 months", "7 months",
 * "2 years" — no "and 0 months" tail. Shared between {@code ColorBoosterReminderScheduler}'s SMS
 * and {@code ColorBoosterWinbackOneOffService}'s email, both of which need to tell a customer
 * how overdue they actually are rather than a generic "about a year" (found live 2026-09-05: the
 * SMS side's own hardcoded "about a year" read as wrong/careless for a customer who was actually
 * 2-3 years overdue). */
public final class TimePeriods {

    private TimePeriods() {
    }

    public static String formatTimeSince(LocalDate from, LocalDate to) {
        Period period = Period.between(from, to);
        int years = period.getYears();
        int months = period.getMonths();
        if (years <= 0) {
            return months + (months == 1 ? " month" : " months");
        }
        String yearsPart = years + (years == 1 ? " year" : " years");
        if (months <= 0) {
            return yearsPart;
        }
        return yearsPart + " and " + months + (months == 1 ? " month" : " months");
    }
}
