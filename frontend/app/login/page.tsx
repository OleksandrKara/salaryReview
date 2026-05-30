'use client';

import { useEffect, useRef } from 'react';
import { useRouter } from 'next/navigation';
import './landing.css';
import { LANDING_MARKUP } from './landingMarkup';

// The AK.LUX.STUDIO landing page (from the Claude Design handoff), serving as the public entry /
// sign-in screen. The marketing markup is rendered verbatim; this component wires the interactions
// (scroll reveal, header state, mobile nav, mock-figure fills, sign-in modal) and points the modal's
// form at the real backend login (POST /api/login), routing by role on success.
export default function LandingPage() {
  const router = useRouter();
  const rootRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const root = rootRef.current;
    if (!root) return;
    const $ = <T extends Element = HTMLElement>(sel: string) => root.querySelector<T>(sel);
    const $$ = (sel: string) => Array.from(root.querySelectorAll<HTMLElement>(sel));
    const cleanups: Array<() => void> = [];
    document.documentElement.classList.add('reveal-ready');

    // Header shadow on scroll
    const header = $('#header');
    const onScroll = () => header?.classList.toggle('scrolled', window.scrollY > 8);
    window.addEventListener('scroll', onScroll, { passive: true });
    onScroll();
    cleanups.push(() => window.removeEventListener('scroll', onScroll));

    // Mobile nav
    const nav = $('#nav');
    const toggle = $('#navToggle');
    const navLinks = $('#navLinks');
    const setNav = (open: boolean) => {
      nav?.classList.toggle('open', open);
      toggle?.setAttribute('aria-expanded', String(open));
    };
    const onToggle = () => setNav(!nav?.classList.contains('open'));
    toggle?.addEventListener('click', onToggle);
    const onNavClick = (e: Event) => { if ((e.target as Element).closest('a')) setNav(false); };
    navLinks?.addEventListener('click', onNavClick);

    // Sign-in modal
    const scrim = $('#signinScrim');
    const modalForm = $('#modalForm');
    const modalSuccess = $('#modalSuccess');
    const openModal = () => {
      scrim?.classList.add('open');
      document.body.style.overflow = 'hidden';
      setNav(false);
      if (modalForm) modalForm.style.display = '';
      if (modalSuccess) modalSuccess.style.display = 'none';
      setTimeout(() => $<HTMLInputElement>('#email')?.focus(), 320);
    };
    const closeModal = () => {
      scrim?.classList.remove('open');
      document.body.style.overflow = '';
    };
    const signinTriggers = $$('[data-signin]');
    const onTrigger = (e: Event) => { e.preventDefault(); openModal(); };
    signinTriggers.forEach((el) => el.addEventListener('click', onTrigger));
    const closeBtn = $('#modalClose');
    closeBtn?.addEventListener('click', closeModal);
    const onScrim = (e: Event) => { if (e.target === scrim) closeModal(); };
    scrim?.addEventListener('click', onScrim);
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape' && scrim?.classList.contains('open')) closeModal(); };
    document.addEventListener('keydown', onKey);
    cleanups.push(() => document.removeEventListener('keydown', onKey));

    // Real login: username + password → POST /api/login, route by role.
    const form = $<HTMLFormElement>('#signinForm');
    const email = $<HTMLInputElement>('#email');
    const pass = $<HTMLInputElement>('#password');
    const emailMsg = $('#emailMsg');
    const passMsg = $('#passMsg');
    const clearErr = (input: HTMLInputElement | null, msg: HTMLElement | null) => {
      input?.classList.remove('err');
      if (msg) msg.textContent = '';
    };
    const onSubmit = async (e: Event) => {
      e.preventDefault();
      clearErr(email, emailMsg);
      clearErr(pass, passMsg);
      const username = email?.value.trim() ?? '';
      const password = pass?.value ?? '';
      let ok = true;
      if (!username) { email?.classList.add('err'); if (emailMsg) emailMsg.textContent = 'Enter your username.'; ok = false; }
      if (!password) { pass?.classList.add('err'); if (passMsg) passMsg.textContent = 'Enter your password.'; ok = false; }
      if (!ok) return;
      const submitBtn = form?.querySelector<HTMLButtonElement>('button[type="submit"]');
      if (submitBtn) submitBtn.disabled = true;
      try {
        const res = await fetch('/api/login', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ username, password }),
        });
        if (!res.ok) {
          pass?.classList.add('err');
          if (passMsg) passMsg.textContent = res.status === 401 ? 'Incorrect username or password.' : 'Sign in failed.';
          return;
        }
        const { role } = (await res.json().catch(() => ({}))) as { role?: string };
        if (modalForm) modalForm.style.display = 'none';
        if (modalSuccess) modalSuccess.style.display = '';
        router.replace(role === 'PROVIDER' ? '/me' : '/reports');
        router.refresh();
      } finally {
        if (submitBtn) submitBtn.disabled = false;
      }
    };
    form?.addEventListener('submit', onSubmit);
    const onInput = (input: HTMLInputElement | null, msg: HTMLElement | null) => () => clearErr(input, msg);
    const onEmailInput = onInput(email, emailMsg);
    const onPassInput = onInput(pass, passMsg);
    email?.addEventListener('input', onEmailInput);
    pass?.addEventListener('input', onPassInput);

    // "Continue with Square" — OAuth sign-in isn't built yet; tell the user rather than fake it.
    const squareBtn = $('#squareBtn');
    const onSquare = () => {
      const foot = $('.m-foot');
      if (foot) foot.innerHTML = 'Square sign-in is coming soon — use your username and password for now.';
    };
    squareBtn?.addEventListener('click', onSquare);

    // Mock-figure fills + scroll reveal (from the design's app.js)
    const fillBars = () => $$('.bar-fill').forEach((b) => requestAnimationFrame(() => { b.style.width = b.dataset.w ?? '0%'; }));
    const sparkCols = $$('#spark .col');
    sparkCols.forEach((c) => { c.style.height = '0%'; });
    const fillSpark = () => sparkCols.forEach((c, i) => setTimeout(() => { c.style.height = c.dataset.h ?? '0%'; }, i * 70));

    const revealEls = $$('.reveal');
    if ('IntersectionObserver' in window) {
      const io = new IntersectionObserver((entries) => {
        entries.forEach((en) => { if (en.isIntersecting) { en.target.classList.add('in'); io.unobserve(en.target); } });
      }, { threshold: 0.08, rootMargin: '0px 0px -6% 0px' });
      revealEls.forEach((el) => io.observe(el));
      cleanups.push(() => io.disconnect());

      const dash = $('#dash');
      if (dash) { const od = new IntersectionObserver((e) => { if (e[0].isIntersecting) { fillBars(); od.disconnect(); } }, { threshold: 0.3 }); od.observe(dash); cleanups.push(() => od.disconnect()); }
      const spark = $('#spark');
      if (spark) { const os = new IntersectionObserver((e) => { if (e[0].isIntersecting) { fillSpark(); os.disconnect(); } }, { threshold: 0.3 }); os.observe(spark); cleanups.push(() => os.disconnect()); }
    } else {
      document.documentElement.classList.add('reveal-force');
      revealEls.forEach((el) => el.classList.add('in'));
      fillBars(); fillSpark();
    }

    return () => {
      cleanups.forEach((fn) => fn());
      toggle?.removeEventListener('click', onToggle);
      navLinks?.removeEventListener('click', onNavClick);
      signinTriggers.forEach((el) => el.removeEventListener('click', onTrigger));
      closeBtn?.removeEventListener('click', closeModal);
      scrim?.removeEventListener('click', onScrim);
      form?.removeEventListener('submit', onSubmit);
      email?.removeEventListener('input', onEmailInput);
      pass?.removeEventListener('input', onPassInput);
      squareBtn?.removeEventListener('click', onSquare);
      document.body.style.overflow = '';
    };
  }, [router]);

  return <div ref={rootRef} className="lustre-landing" dangerouslySetInnerHTML={{ __html: LANDING_MARKUP }} />;
}
