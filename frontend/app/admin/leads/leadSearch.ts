export type SearchFields = {
  name?: string | null;
  phoneNumber?: string | null;
  email?: string | null;
  source?: string | null;
  lastNote?: string | null;
};

export type ParsedSearch = {
  raw: string;
  tokens: string[];
  /** True when the whole query is a phone-like string: digits with spaces, brackets, dashes, dots or a leading plus. */
  phoneLike: boolean;
  /** All digits of the query, without the US country code. */
  digits: string;
};

const PHONE_LIKE = /^[\d\s()+.\-‐-―/]+$/;
const DIGITS_TOKEN = /^[\d()+.\-‐-―/]+$/;

export function foldText(s: string | null | undefined): string {
  return (s ?? '').normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase().replace(/\s+/g, ' ').trim();
}

export function digitsOf(s: string | null | undefined): string {
  return (s ?? '').replace(/\D/g, '');
}

/** Drops a leading US country code so 1 (619) 454-0318, +1 619 454 0318 and 619-454-0318 all compare equal. */
export function withoutCountryCode(d: string): string {
  return d.length >= 11 && d.startsWith('1') ? d.slice(1) : d;
}

export function parseSearch(q: string): ParsedSearch {
  const raw = q.trim();
  const phoneLike = raw !== '' && PHONE_LIKE.test(raw) && /\d/.test(raw);
  return {
    raw,
    tokens: foldText(raw).split(' ').filter(Boolean),
    phoneLike,
    digits: phoneLike ? withoutCountryCode(digitsOf(raw)) : '',
  };
}

function phoneMatches(phone: string | null | undefined, wantedDigits: string): boolean {
  if (!wantedDigits) return false;
  const have = digitsOf(phone);
  if (!have) return false;
  return withoutCountryCode(have).includes(wantedDigits) || have.includes(wantedDigits);
}

/** Phone numbers are matched by digits only; names, emails, sources and notes by case- and accent-insensitive words. */
export function leadMatches(l: SearchFields, p: ParsedSearch): boolean {
  if (p.raw === '') return true;
  const text = [l.name, l.email, l.source, l.lastNote].map(foldText);
  if (p.phoneLike) {
    if (phoneMatches(l.phoneNumber, p.digits)) return true;
    const folded = foldText(p.raw);
    return text.slice(0, 2).some((v) => v.includes(folded));
  }
  return p.tokens.every((tok) => {
    if (DIGITS_TOKEN.test(tok) && /\d/.test(tok) && phoneMatches(l.phoneNumber, withoutCountryCode(digitsOf(tok)))) return true;
    return text.some((v) => v.includes(tok)) || foldText(l.phoneNumber).includes(tok);
  });
}
