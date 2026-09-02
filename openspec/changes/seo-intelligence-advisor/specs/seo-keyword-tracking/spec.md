## ADDED Requirements

### Requirement: Owners curate a list of tracked keywords, each with an explicit location
The system SHALL let an owner add, edit, and remove tracked keywords (10-50 typical), each with a
required location (defaulting to Downtown San Diego / 92101, editable) and device (mobile/desktop),
independent of Search Console's own query data.

#### Scenario: Owner adds a tracked keyword
- **WHEN** an owner submits a keyword with a location and device
- **THEN** a `seo_tracked_keyword` row is created for that business, starting with no rank history
  until the next scheduled or manual check

#### Scenario: Owner removes a tracked keyword
- **WHEN** an owner deactivates a tracked keyword
- **THEN** it stops being checked on future syncs, and its historical `seo_rank_snapshot` rows are
  retained (not deleted) so past history remains viewable

### Requirement: Tracked keyword rank is a real, location-targeted SERP position, distinct from Search Console's average position
The system SHALL fetch each active tracked keyword's real SERP position from a third-party
rank-tracking provider, scoped to its stored location and device, and SHALL label it visibly and
distinctly from any Search Console-derived "average position" metric shown elsewhere in the app.

#### Scenario: Displaying a tracked keyword's rank
- **WHEN** a tracked keyword has at least one `seo_rank_snapshot` row
- **THEN** its current position, location, and device are shown together, with a label
  distinguishing it as a "tracked SERP position" and never merged with or presented as Search
  Console's average position

#### Scenario: No rank-tracking provider is configured
- **WHEN** a business has tracked keywords but no rank-tracking provider credential configured
- **THEN** the UI shows an explicit "not connected" state per keyword, never a fabricated or
  zero-filled position

### Requirement: Ranking history is visualized with a clear trend indicator
The system SHALL show, for each tracked keyword, its current position, position N days ago (for
7/30/90/365-day windows), best historical position, and first-tracked position, with a simple
trend chart and a 🟢 improving / 🔴 declining / ⚪ stable indicator.

#### Scenario: A keyword's position improves over the selected window
- **WHEN** an owner selects a 30-day window for a tracked keyword
- **THEN** the chart shows the position trend over that window and the indicator reflects the net
  direction of change

### Requirement: Rank checks run on a daily schedule and never block on one keyword's or one business's failure
The system SHALL check all active tracked keywords for all businesses with a configured
rank-tracking credential on a daily schedule, isolating failures per business the same way existing
SEO sync jobs do, and SHALL NOT call the rank-tracking provider more often than the configured
schedule outside of a keyword just being added.

#### Scenario: One business's provider credential is invalid
- **WHEN** business A's rank-tracking credential is invalid or revoked
- **THEN** business B's scheduled rank check for the same run still completes normally, and
  business A's failure is recorded without raising an unhandled exception
