export const SMS_MAX_CHARS = 1600;
export const SMS_SEGMENT_CHARS = 160;

export type BodyPart = { text: string; bold: boolean };

// A *bold* pair must sit on one line, wrap non-space text and not touch spaces inside the marks,
// so "5 * 3 * 2", lone asterisks and "* bullet" lines stay literal. Both "*x*" and "**x**" match.
const BOLD_RE = /(\*{1,2})(?=\S)([^*\n]*?\S)\1(?!\*)/g;

export function parseBold(body: string): BodyPart[] {
  const parts: BodyPart[] = [];
  let last = 0;
  for (const m of body.matchAll(BOLD_RE)) {
    const start = m.index ?? 0;
    if (start > last) parts.push({ text: body.slice(last, start), bold: false });
    parts.push({ text: m[2], bold: true });
    last = start + m[0].length;
  }
  if (last < body.length) parts.push({ text: body.slice(last), bold: false });
  return parts;
}

export function hasBoldMarkers(body: string): boolean {
  return parseBold(body).some((p) => p.bold);
}

export function stripBoldMarkers(body: string): string {
  return parseBold(body).map((p) => p.text).join('');
}

export function smsLengthState(body: string): 'ok' | 'long' | 'too_long' {
  if (body.length > SMS_MAX_CHARS) return 'too_long';
  if (body.length > SMS_SEGMENT_CHARS * 4) return 'long';
  return 'ok';
}

export function sendFailureMessage(reason: string | null | undefined): string {
  if (reason === 'too_long') return `Not sent: the message is longer than ${SMS_MAX_CHARS} characters. Shorten it or split it into two messages.`;
  if (reason === 'blocked') return 'Not sent: this number is blocked.';
  if (reason === 'duplicate_recent') return 'Not sent: the same text was just sent to this client. Check the thread before sending again.';
  return 'Not sent. Nothing was delivered to the client. Your text is still in the box, so you can try again.';
}
