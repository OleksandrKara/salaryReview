import type { Margins } from '../../../lib/types';

/**
 * Rough profit after the costs we can name: the master's share and the card fee come off revenue,
 * and materials are charged once per customer who paid a deposit (a procedure was booked).
 * Rent, insurance and other overhead are not included.
 */
export function profitAfterCosts(revenue: number, procedures: number, m: Margins): number {
  const share = (m.masterPct + m.squarePct) / 100;
  return revenue * (1 - share) - m.materialPerProcedure * procedures;
}

/** Return on marketing investment in percent, or null when nothing was spent. */
export function romiPercent(revenue: number, spend: number, procedures: number, m: Margins): number | null {
  if (spend <= 0) return null;
  return ((profitAfterCosts(revenue, procedures, m) - spend) / spend) * 100;
}
