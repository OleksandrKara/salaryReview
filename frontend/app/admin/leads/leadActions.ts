import type { Lead } from '../../lib/types';

export const DAY = 86_400_000;
export const LAPSED_AFTER_DAYS = 30;

export function isActive(l: Pick<Lead, 'status'>): boolean {
  return l.status === 'NEW' || l.status === 'CONTACTED';
}

export function isOverdue(l: Pick<Lead, 'status' | 'callbackAt'>, now: number): boolean {
  return isActive(l) && l.callbackAt != null && new Date(l.callbackAt).getTime() <= now;
}

export function hasNextStep(l: Pick<Lead, 'status' | 'callbackAt'>, now: number): boolean {
  return isActive(l) && l.callbackAt != null && new Date(l.callbackAt).getTime() > now;
}

export function lacksNextStep(l: Pick<Lead, 'status' | 'callbackAt'>, now: number): boolean {
  return isActive(l) && !hasNextStep(l, now);
}

export function isLapsed(l: Pick<Lead, 'status' | 'lastVisitAt'>, now: number): boolean {
  return isActive(l) && l.lastVisitAt != null && now - new Date(l.lastVisitAt).getTime() > LAPSED_AFTER_DAYS * DAY;
}

export type Primary = 'CLOSE' | 'REACHED' | null;
export interface ActionPlan { call: boolean; text: boolean; primary: Primary; reachedSecondary: boolean; noAnswer: boolean; spam: boolean }

export function actionPlan(l: Pick<Lead, 'status' | 'callbackAt' | 'closeReady' | 'phoneNumber'>, now: number): ActionPlan {
  const active = isActive(l);
  const phone = l.phoneNumber != null && l.phoneNumber !== '';
  const due = isOverdue(l, now) || l.status === 'NEW';
  const primary: Primary = !active ? null : l.closeReady ? 'CLOSE' : due ? 'REACHED' : null;
  return {
    call: active && phone,
    text: active && phone,
    primary,
    reachedSecondary: active && primary !== 'REACHED',
    noAnswer: active && !l.closeReady,
    spam: active,
  };
}

/**
 * A person who left their details on a booking or request form but never booked: salaryReview creates these leads with the
 * detail "Website contact, no booking yet" (the 2026-11-05 backfill used "Imported from website contacts (no booking)").
 * Shown while the lead is still open and no consultation is booked, so staff know to help them finish the booking.
 */
export function leftDetailsOnly(l: { detail: string | null; status: string; consultState: string | null }): boolean {
  return (l.status === 'NEW' || l.status === 'CONTACTED') && l.consultState == null
    && !!l.detail && (l.detail.startsWith('Website contact, no booking yet') || l.detail.startsWith('Imported from website contacts (no booking)'));
}
