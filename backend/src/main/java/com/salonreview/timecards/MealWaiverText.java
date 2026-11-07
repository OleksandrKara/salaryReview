package com.salonreview.timecards;

/** The written meal period waiver a W2 technician can sign once. Version bumps whenever the wording changes. */
public final class MealWaiverText {

    private MealWaiverText() {}

    public static final String VERSION = "2026-11-v1";

    public static String body(String employerName) {
        return """
                MEAL PERIOD WAIVER (California Labor Code section 512(a); IWC Wage Order 2, section 11)

                Employer: %s

                1. I understand that California law gives me a 30 minute, uninterrupted, duty-free meal period when I work more than 5 hours in a day, and that I am free to leave the work area during it.
                2. I understand that on a day when my total work time is no more than 6 hours, the meal period may be waived by mutual consent of my employer and me.
                3. I voluntarily agree to waive my meal period on a day when my total work time is no more than 6 hours, and my employer agrees. On each such day I will mark the waiver on my timecard confirmation.
                4. This waiver does not apply to any day when I work more than 6 hours. On those days I must take a 30 minute meal period that starts before the end of my fifth hour of work.
                5. Signing this waiver is not a condition of my employment. I may take a meal period on any day, even a short one, and my employer will not discourage me from doing so.
                6. I may revoke this waiver at any time, effective immediately, by telling my employer in writing or through the timecard system. My employer will not retaliate against me for revoking it.
                7. My employer keeps a copy of this signed waiver, and I can view it in the timecard system at any time.
                """.formatted(employerName);
    }
}
