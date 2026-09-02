## ADDED Requirements

**Note (2026-09-02):** this capability was redesigned to a zero-cost scope after the owner
declined to pay for an external SEO data provider — see design.md D2/D9. Everywhere below,
"competitor metric" means only PageSpeed/Core Web Vitals (automated, free, first-party — the same
integration already used for our own business) and owner-entered Google Business Profile
rating/review count (manual, never auto-synced — there is no free API for a competitor's own GBP
data). Keyword-overlap and backlink comparison are explicitly out of scope, not silently omitted.

### Requirement: Owners configure their own competitor list; no competitor is hardcoded
The system SHALL let an owner add, edit, deactivate, and remove competitor entries (name, website,
location, notes, active flag, and a manually-entered Google Business Profile rating/review count),
with no competitor name or website ever hardcoded in application code.

#### Scenario: Owner adds a competitor
- **WHEN** an owner submits a competitor's name and website
- **THEN** a `seo_competitor` row is created for that business, with no PageSpeed comparison data
  until the next scheduled sync

#### Scenario: Owner deactivates a competitor
- **WHEN** an owner marks a competitor inactive
- **THEN** it is excluded from future PageSpeed syncs and from the Competitors sub-view's active
  list, but its historical `seo_competitor_page_snapshot` rows are retained

#### Scenario: Owner updates a competitor's GBP rating/review count
- **WHEN** an owner edits the manually-entered rating or review count for a competitor
- **THEN** the stored value and its "owner-entered" timestamp update immediately — no background
  job ever overwrites this field, since no free API can supply it automatically

### Requirement: Every competitor metric is labeled with its source, and unavailable dimensions are shown as unavailable, not omitted
The system SHALL label every competitor metric shown as either "PageSpeed Insights" (automated,
first-party) or "owner-entered" (manual GBP fields), and SHALL show keyword-overlap/backlink
comparison as an explicit "not available without a paid SEO tool" state rather than omitting it
silently or fabricating a value.

#### Scenario: A competitor's PageSpeed data hasn't synced yet
- **WHEN** a competitor was just added and the weekly PageSpeed sync hasn't run yet
- **THEN** the UI shows an explicit "not synced yet" state for that competitor's CWV row, never a
  blank, zero, or estimated placeholder presented as real

#### Scenario: A metric is derived from a first-party source
- **WHEN** a competitor's Core Web Vitals are fetched (the existing PageSpeed Insights integration
  scores any public URL, not just the owner's own site)
- **THEN** it is labeled as coming from PageSpeed Insights directly, the same source label already
  used for the owner's own CWV cards

#### Scenario: Keyword-overlap comparison is requested
- **WHEN** an owner views the Competitors sub-view
- **THEN** any keyword-overlap/backlink dimension is shown as "not available without a paid SEO
  tool," never blank or silently absent from the layout

### Requirement: Competitor comparison never blends owner-entered or third-party-adjacent data with Google's own first-party data
The system SHALL keep the manually-entered GBP fields visually and structurally distinct from
PageSpeed-sourced and Search-Console/GA4-sourced metrics for our own business, even when shown
side by side in the same comparison view.

#### Scenario: Side-by-side comparison view
- **WHEN** an owner views our business's Core Web Vitals next to a competitor's
- **THEN** both are labeled with their respective source (both "PageSpeed Insights" in this case),
  and the competitor's owner-entered GBP rating is visually distinguished from any automated metric
