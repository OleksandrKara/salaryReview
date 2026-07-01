'use client';

import { useEffect, useRef, useState } from 'react';
// landing.css is imported app-wide in the root layout (the salon theme applies to every page).
import { LANDING_MARKUP } from './landingMarkup';
import { Spinner } from './components/Spinner';

// AK.LUX.STUDIO landing (Claude Design) — the homepage at `/`. Marketing markup is injected as-is;
// the sign-in modal is real React so the buttons reliably open it. Posts to /api/login, routes by role.
export default function Landing() {
  const rootRef = useRef<HTMLDivElement>(null);
  const wasOpen = useRef(false); // track open→close so we only re-reveal on an actual close
  const [modalOpen, setModalOpen] = useState(false);
  const [done, setDone] = useState(false);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  // Open the modal from any [data-signin] button inside the injected markup (event delegation).
  function onRootClick(e: React.MouseEvent) {
    const trigger = (e.target as HTMLElement).closest('[data-signin]');
    if (trigger) {
      e.preventDefault();
      setError('');
      setDone(false);
      setModalOpen(true);
    }
  }

  // Lock scroll while open; Esc to close.
  useEffect(() => {
    document.body.style.overflow = modalOpen ? 'hidden' : '';
    // Closing the modal re-renders the landing; its scroll-reveal blocks (opacity:0 until `.in`) can be
    // left invisible afterwards. On an actual close, re-assert `.in` on the live blocks so nothing is
    // ever stranded as an empty block. (Only on close — the first load still plays the reveal animation.)
    if (!modalOpen && wasOpen.current) {
      rootRef.current?.querySelectorAll<HTMLElement>('.reveal').forEach((el) => el.classList.add('in'));
    }
    wasOpen.current = modalOpen;
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setModalOpen(false); };
    window.addEventListener('keydown', onKey);
    return () => { window.removeEventListener('keydown', onKey); document.body.style.overflow = ''; };
  }, [modalOpen]);

  // Design interactions: header shadow, mobile nav, scroll reveal, mock-figure fills.
  useEffect(() => {
    const root = rootRef.current;
    if (!root) return;
    const $$ = (s: string) => Array.from(root.querySelectorAll<HTMLElement>(s));
    document.documentElement.classList.add('reveal-ready');

    const header = root.querySelector('#header');
    const onScroll = () => header?.classList.toggle('scrolled', window.scrollY > 8);
    window.addEventListener('scroll', onScroll, { passive: true });
    onScroll();

    const nav = root.querySelector('#nav');
    const toggle = root.querySelector('#navToggle');
    const onToggle = () => { const open = !nav?.classList.contains('open'); nav?.classList.toggle('open', open); toggle?.setAttribute('aria-expanded', String(open)); };
    toggle?.addEventListener('click', onToggle);

    const fillBars = () => $$('.bar-fill').forEach((b) => requestAnimationFrame(() => { b.style.width = b.dataset.w ?? '0%'; }));
    const spark = $$('#spark .col');
    spark.forEach((c) => { c.style.height = '0%'; });
    const fillSpark = () => spark.forEach((c, i) => setTimeout(() => { c.style.height = c.dataset.h ?? '0%'; }, i * 70));

    const reveal = $$('.reveal');
    const io = new IntersectionObserver((es) => es.forEach((en) => { if (en.isIntersecting) { en.target.classList.add('in'); io.unobserve(en.target); } }), { threshold: 0.08, rootMargin: '0px 0px -6% 0px' });
    reveal.forEach((el) => io.observe(el));
    setTimeout(() => { fillBars(); fillSpark(); }, 200);

    // Safety net: content must never stay invisible. If the observer hasn't revealed an element
    // (offscreen, fast scroll, observer quirk), force it visible so every block — including the
    // pricing card and final CTA at the bottom — always shows.
    const safety = setTimeout(() => reveal.forEach((el) => el.classList.add('in')), 1500);

    return () => { window.removeEventListener('scroll', onScroll); toggle?.removeEventListener('click', onToggle); io.disconnect(); clearTimeout(safety); };
  }, []);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    const f = e.currentTarget as HTMLFormElement;
    const username = (f.elements.namedItem('username') as HTMLInputElement).value.trim();
    const password = (f.elements.namedItem('password') as HTMLInputElement).value;
    if (!username || !password) { setError('Enter your username and password.'); return; }
    setError('');
    setBusy(true);
    try {
      const res = await fetch('/api/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username, password }) });
      if (!res.ok) { setError(res.status === 401 ? 'Incorrect username or password.' : 'Sign in failed.'); return; }
      const { role } = (await res.json().catch(() => ({}))) as { role?: string };
      setDone(true);
      // Full document load (not router.replace) so the server renders the root layout — and its
      // mandatory language/SOP gate — from scratch. A client navigation would reuse the existing
      // layout and briefly show the home page before the gate mounts.
      window.location.assign(role === 'PROVIDER' ? '/me' : role === 'MANAGER' ? '/manager' : '/reports');
    } finally {
      setBusy(false);
    }
  }

  // A link that isn't wired up yet — shown but inert.
  const soon = (label: string) => (
    <a href="#" aria-disabled="true" title="Coming soon" onClick={(e) => e.preventDefault()} style={{ cursor: 'default', opacity: 0.6 }}>{label}</a>
  );

  return (
    <>
      <div ref={rootRef} className="lustre-landing" onClick={onRootClick} dangerouslySetInnerHTML={{ __html: LANDING_MARKUP }} />

      <div className={`modal-scrim${modalOpen ? ' open' : ''}`} role="dialog" aria-modal="true" aria-label="Sign in"
        onClick={(e) => { if (e.target === e.currentTarget) setModalOpen(false); }}>
        <div className="modal">
          <button className="m-close" aria-label="Close" onClick={() => setModalOpen(false)}>
            <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5"><path d="M6 6l12 12M18 6L6 18" /></svg>
          </button>
          {!done ? (
            <div>
              <div className="m-mark">AK.LUX.STUDIO</div>
              <h3>Welcome back</h3>
              <p className="m-sub">Sign in to your studio dashboard.</p>
              <form onSubmit={submit} noValidate>
                <div className="field">
                  <label htmlFor="username">Email or username</label>
                  <input type="text" id="username" name="username" placeholder="your username" autoComplete="username" autoFocus />
                </div>
                <div className="field">
                  <label htmlFor="password">Password</label>
                  <input type="password" id="password" name="password" placeholder="••••••••" autoComplete="current-password" />
                </div>
                {error && <div className="msg" style={{ marginBottom: '0.8rem' }}>{error}</div>}
                <div className="m-row">
                  <label className="checkbox"><input type="checkbox" defaultChecked /> Remember me</label>
                  {soon('Forgot password?')}
                </div>
                <button type="submit" className="btn full" disabled={busy}>
                  {busy ? (
                    <span style={{ display: 'inline-flex', alignItems: 'center', justifyContent: 'center', gap: '0.5rem' }}>
                      <Spinner className="h-4 w-4" /> Signing in…
                    </span>
                  ) : 'Sign in'}
                </button>
              </form>
              <div className="m-divider">or</div>
              <button className="m-square" disabled title="Coming soon" style={{ cursor: 'default', opacity: 0.6 }}>
                <svg viewBox="0 0 24 24" fill="none"><rect x="3" y="3" width="18" height="18" rx="4" fill="currentColor" /><rect x="9" y="9" width="6" height="6" rx="1.4" fill="var(--paper)" /></svg>
                Continue with Square <span style={{ fontSize: '0.7em', letterSpacing: '0.1em' }}>(soon)</span>
              </button>
              <p className="m-foot">New to AK.LUX.STUDIO? {soon('Book a demo')}</p>
            </div>
          ) : (
            <div className="m-success">
              <div className="ok"><svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><path d="M5 12l4 4L19 7" /></svg></div>
              <h3 style={{ marginTop: 0 }}>You&apos;re in</h3>
              <p className="m-sub" style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: '0.5rem' }}>
                <Spinner className="h-4 w-4" /> Taking you to your dashboard…
              </p>
            </div>
          )}
        </div>
      </div>
    </>
  );
}
