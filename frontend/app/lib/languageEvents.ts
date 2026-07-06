import type { Language } from './types';

// Bridges an account language change to listeners outside the component that made it — e.g. the
// assistant widget lives in the root layout and isn't remounted or re-fetched by LanguageSwitch's
// router.refresh() (that only re-runs server components on the current route; it doesn't change
// the URL, so effects keyed on pathname never re-fire). A same-tab CustomEvent is the simplest way
// to notify everyone immediately, without a page reload or a full context-provider rewiring.
const LANGUAGE_CHANGE_EVENT = 'salonreview:language-changed';

export function announceLanguageChange(language: Language) {
  window.dispatchEvent(new CustomEvent<Language>(LANGUAGE_CHANGE_EVENT, { detail: language }));
}

export function onLanguageChange(handler: (language: Language) => void): () => void {
  const listener = (e: Event) => handler((e as CustomEvent<Language>).detail);
  window.addEventListener(LANGUAGE_CHANGE_EVENT, listener);
  return () => window.removeEventListener(LANGUAGE_CHANGE_EVENT, listener);
}
