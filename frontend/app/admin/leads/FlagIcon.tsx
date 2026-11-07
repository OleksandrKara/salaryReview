/** Windows has no flag emoji, so the flag is drawn from the regional-indicator pair as a bundled SVG. */
export function flagCode(flag: string | null | undefined): string | null {
  if (!flag) return null;
  const cps = Array.from(flag).map((c) => c.codePointAt(0) ?? 0);
  if (cps.length !== 2 || cps.some((c) => c < 0x1f1e6 || c > 0x1f1ff)) return null;
  return cps.map((c) => String.fromCharCode(c - 0x1f1e6 + 97)).join('');
}

export default function FlagIcon({ flag, className }: { flag: string; className?: string }) {
  const code = flagCode(flag);
  if (!code) return <span aria-hidden className={className}>{flag}</span>;
  // eslint-disable-next-line @next/next/no-img-element
  return <img src={`/flags/${code}.svg`} alt="" aria-hidden className={className} loading="lazy" />;
}
