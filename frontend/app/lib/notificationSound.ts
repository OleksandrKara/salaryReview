'use client';

// A short two-tone chime for new inbound messages (see MessagesNotifierIcon), synthesized via the
// Web Audio API rather than a shipped audio file — no binary asset to fetch, and it plays
// instantly. Browsers block audio autoplay until the page has seen at least one real user gesture
// (click/tap/keydown); `primeAudio()` is meant to be called from inside that first gesture's event
// handler so later, gesture-less calls from the polling loop are actually allowed to play.

let audioContext: AudioContext | null = null;

function getContext(): AudioContext | null {
  if (typeof window === 'undefined') return null;
  const Ctor = window.AudioContext ?? (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
  if (!Ctor) return null;
  if (!audioContext) audioContext = new Ctor();
  return audioContext;
}

/** Call once, inside a real user-gesture event handler (click/keydown/touchstart), to unlock audio
 * playback for the rest of the session — most browsers suspend a freshly-created AudioContext
 * until this happens at least once. */
export function primeAudio(): void {
  const ctx = getContext();
  if (ctx && ctx.state === 'suspended') void ctx.resume();
}

function tone(ctx: AudioContext, freq: number, startAt: number, duration: number): void {
  const osc = ctx.createOscillator();
  const gain = ctx.createGain();
  osc.type = 'sine';
  osc.frequency.value = freq;
  // Exponential ramps (not linear) so the attack/decay sounds like a natural chime rather than a
  // harsh on/off click — exponentialRampToValueAtTime can't target exactly 0, hence 0.0001.
  gain.gain.setValueAtTime(0.0001, startAt);
  gain.gain.exponentialRampToValueAtTime(0.2, startAt + 0.01);
  gain.gain.exponentialRampToValueAtTime(0.0001, startAt + duration);
  osc.connect(gain);
  gain.connect(ctx.destination);
  osc.start(startAt);
  osc.stop(startAt + duration);
}

/** Best-effort: silently no-ops if the browser blocks it (no prior user gesture on this page yet)
 * or has no Web Audio support at all — a missed chime is never worth surfacing an error over. */
export function playNotificationSound(): void {
  try {
    const ctx = getContext();
    if (!ctx) return;
    if (ctx.state === 'suspended') void ctx.resume();
    const now = ctx.currentTime;
    tone(ctx, 880, now, 0.15);
    tone(ctx, 1175, now + 0.12, 0.18);
  } catch {
    // Best-effort — see doc comment above.
  }
}
