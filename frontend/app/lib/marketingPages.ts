import type { MarketingLandingPage } from './types';

/** Retired pages stay selectable (their history is kept) but are labelled. */
export function pageOptionLabel(p: MarketingLandingPage): string {
  return p.active === false ? `${p.name} (inactive)` : p.name;
}

/** First active page, matching the backend default; undefined when none is active. */
export function firstActiveSlug(pages: MarketingLandingPage[]): string | undefined {
  return pages.find((p) => p.active !== false)?.slug;
}
