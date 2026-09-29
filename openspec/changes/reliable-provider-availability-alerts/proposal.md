# Reliable provider availability alerts

The current detector attributes an expired or occupied online booking start to a provider closing their calendar. AK.LUX.NAILS has eight saved events, including five inside Square's current two-hour booking cutoff and a repeated start. Replace this inference with a conservative, confirmed large loss of online booking opportunities.

The default policy is a 24-hour notice threshold, a continuous lost-start span of at least 240 minutes, confirmation after 20 minutes, and at most one notification per business/provider/affected local date. Start in observation-only mode. Messages describe observed availability, without asserting an actor, working hours closed, or prior approval.

Verification: pure detector and scheduler tests; fake HTTP Square/Telegram response tests; isolated fresh pgvector/pg16 migration/state/tenant/delivery tests; backend verify and frontend typecheck/build. Square checks are read-only; no test uses production credentials. The previous read-only Square checks are recorded in docs/provider-schedule-closure-analysis-2026-09-29.md.

## Non-goals

Payroll changes, Square writes, interpreting a last free slot as a full closed shift, automatic attribution to a staff member, a new shift/approval workflow, enabling Business 2, and bypassing the production observation period.
