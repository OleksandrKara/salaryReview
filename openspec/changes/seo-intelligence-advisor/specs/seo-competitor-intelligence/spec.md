## ADDED Requirements

### Requirement: Owners configure their own competitor list; no competitor is hardcoded
The system SHALL let an owner add, edit, deactivate, and remove competitor entries (name, website,
Google Business Profile reference, location, notes, active flag), with no competitor name or
website ever hardcoded in application code.

#### Scenario: Owner adds a competitor
- **WHEN** an owner submits a competitor's name and website
- **THEN** a `seo_competitor` row is created for that business, with no comparison data until the
  next sync

#### Scenario: Owner deactivates a competitor
- **WHEN** an owner marks a competitor inactive
- **THEN** it is excluded from future comparison syncs and from the Competitors sub-view's active
  list, but its historical `seo_competitor_metric` rows are retained

### Requirement: Every competitor metric is labeled with its source and confidence
The system SHALL store and display, for every competitor metric, which source produced it (e.g.
"DataForSEO," "Google Business Profile," "PageSpeed Insights — first-party, same as our own data")
and a confidence level, and SHALL NOT display any competitor metric without both.

#### Scenario: A competitor metric is missing
- **WHEN** a configured dimension (e.g. keyword overlap) has no data available for a given
  competitor from any connected source
- **THEN** the UI shows an explicit "no data available" state for that cell, never a blank,
  zero, or estimated placeholder presented as real

#### Scenario: A metric is derived from a first-party source
- **WHEN** a competitor's Core Web Vitals or PageSpeed score is fetched (achievable with the
  existing PageSpeed Insights integration, since it can score any public URL)
- **THEN** it is labeled as coming from PageSpeed Insights directly, not attributed to the
  third-party rank-tracking provider

### Requirement: Competitor comparison never blends third-party estimates with Google's own first-party data
The system SHALL keep competitor-sourced metrics visually and structurally distinct from
Search-Console/GA4/PageSpeed-sourced metrics for our own business, even when shown side by side in
the same comparison view.

#### Scenario: Side-by-side comparison view
- **WHEN** an owner views our business's Core Web Vitals next to a competitor's
- **THEN** both are labeled with their respective source, and no combined/blended score implies
  false equivalence between first-party and third-party data
