export const CONFIRM_ARM_MS = 400;

export function phoneLast4(phone: string): string {
  const digits = phone.replace(/\D/g, '');
  return digits.slice(-4);
}

export function maskedPhone(phone: string): string {
  const last4 = phoneLast4(phone);
  return last4 ? `•••• ${last4}` : '';
}

/** Confirm is ignored for a short moment after the dialog opens, so a double-tap on Send can never confirm. */
export function confirmArmed(openedAt: number, now: number): boolean {
  return now - openedAt >= CONFIRM_ARM_MS;
}

export function channelFor(photoCount: number): 'SMS' | 'MMS' {
  return photoCount > 0 ? 'MMS' : 'SMS';
}
