'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { t } from '../../../lib/i18n';
import type { Language } from '../../../lib/types';

export type ViewerImage = { url: string; at: string; label: string };

const MAX_SCALE = 8;
type Pt = { x: number; y: number };

export default function ImageViewer({ images, start, language, onClose }: { images: ViewerImage[]; start: number; language: Language | null; onClose: () => void }) {
  const [index, setIndex] = useState(start);
  const [view, setView] = useState({ s: 1, x: 0, y: 0 });
  const [loaded, setLoaded] = useState(false);
  const viewRef = useRef(view);
  viewRef.current = view;
  const pointers = useRef(new Map<number, Pt>());
  const gesture = useRef<{ d: number; s: number; mid: Pt; x: number; y: number } | null>(null);
  const drag = useRef<{ x: number; y: number; vx: number; vy: number; t: number; moved: boolean } | null>(null);
  const lastTap = useRef(0);
  const box = useRef<HTMLDivElement>(null);
  const img = images[index];

  const go = useCallback((to: number) => {
    if (to < 0 || to >= images.length) return;
    setIndex(to);
    setView({ s: 1, x: 0, y: 0 });
    setLoaded(false);
  }, [images.length]);

  const clamp = (s: number, x: number, y: number) => {
    const el = box.current;
    if (!el || s <= 1) return { s: 1, x: 0, y: 0 };
    const lx = (el.clientWidth * (s - 1)) / 2;
    const ly = (el.clientHeight * (s - 1)) / 2;
    return { s, x: Math.max(-lx, Math.min(lx, x)), y: Math.max(-ly, Math.min(ly, y)) };
  };

  const zoomAt = (ns: number, cx: number, cy: number) => {
    const el = box.current;
    if (!el) return;
    const r = el.getBoundingClientRect();
    const { s, x, y } = viewRef.current;
    const k = Math.max(1, Math.min(MAX_SCALE, ns));
    const px = cx - (r.left + r.width / 2);
    const py = cy - (r.top + r.height / 2);
    setView(clamp(k, px - ((px - x) / s) * k, py - ((py - y) / s) * k));
  };

  useEffect(() => {
    const prev = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    const key = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
      else if (e.key === 'ArrowLeft') go(index - 1);
      else if (e.key === 'ArrowRight') go(index + 1);
      else if (e.key === '+' || e.key === '=') zoomAt(viewRef.current.s * 1.5, window.innerWidth / 2, window.innerHeight / 2);
      else if (e.key === '-') zoomAt(viewRef.current.s / 1.5, window.innerWidth / 2, window.innerHeight / 2);
    };
    window.addEventListener('keydown', key);
    return () => { document.body.style.overflow = prev; window.removeEventListener('keydown', key); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [index, go, onClose]);

  useEffect(() => {
    const el = box.current;
    if (!el) return;
    const wheel = (e: WheelEvent) => {
      e.preventDefault();
      zoomAt(viewRef.current.s * Math.exp(-e.deltaY * 0.0025), e.clientX, e.clientY);
    };
    el.addEventListener('wheel', wheel, { passive: false });
    return () => el.removeEventListener('wheel', wheel);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const down = (e: React.PointerEvent) => {
    (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
    pointers.current.set(e.pointerId, { x: e.clientX, y: e.clientY });
    const v = viewRef.current;
    if (pointers.current.size === 2) {
      const [a, b] = [...pointers.current.values()];
      gesture.current = { d: Math.hypot(a.x - b.x, a.y - b.y) || 1, s: v.s, mid: { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 }, x: v.x, y: v.y };
      drag.current = null;
    } else {
      drag.current = { x: e.clientX, y: e.clientY, vx: v.x, vy: v.y, t: Date.now(), moved: false };
    }
  };

  const move = (e: React.PointerEvent) => {
    if (!pointers.current.has(e.pointerId)) return;
    pointers.current.set(e.pointerId, { x: e.clientX, y: e.clientY });
    if (pointers.current.size === 2 && gesture.current) {
      const [a, b] = [...pointers.current.values()];
      const g = gesture.current;
      const k = Math.max(1, Math.min(MAX_SCALE, (g.s * (Math.hypot(a.x - b.x, a.y - b.y) || 1)) / g.d));
      const el = box.current;
      if (!el) return;
      const r = el.getBoundingClientRect();
      const px = g.mid.x - (r.left + r.width / 2);
      const py = g.mid.y - (r.top + r.height / 2);
      const mx = (a.x + b.x) / 2 - g.mid.x;
      const my = (a.y + b.y) / 2 - g.mid.y;
      setView(clamp(k, px - ((px - g.x) / g.s) * k + mx, py - ((py - g.y) / g.s) * k + my));
    } else if (drag.current && pointers.current.size === 1) {
      const d = drag.current;
      const dx = e.clientX - d.x;
      const dy = e.clientY - d.y;
      if (Math.abs(dx) + Math.abs(dy) > 6) d.moved = true;
      if (viewRef.current.s > 1) setView(clamp(viewRef.current.s, d.vx + dx, d.vy + dy));
    }
  };

  const up = (e: React.PointerEvent) => {
    const d = drag.current;
    pointers.current.delete(e.pointerId);
    if (pointers.current.size < 2) gesture.current = null;
    if (!d || pointers.current.size > 0) return;
    drag.current = null;
    const dx = e.clientX - d.x;
    const dy = e.clientY - d.y;
    if (viewRef.current.s <= 1.01 && d.moved && Math.abs(dx) > 50 && Math.abs(dx) > Math.abs(dy) * 1.5) {
      go(dx < 0 ? index + 1 : index - 1);
      return;
    }
    if (viewRef.current.s <= 1.01 && d.moved && dy > 90 && Math.abs(dy) > Math.abs(dx) * 1.5) {
      onClose();
      return;
    }
    if (!d.moved) {
      const now = Date.now();
      if (now - lastTap.current < 300) {
        lastTap.current = 0;
        if (viewRef.current.s > 1.01) setView({ s: 1, x: 0, y: 0 });
        else zoomAt(3, e.clientX, e.clientY);
      } else lastTap.current = now;
    }
  };

  const btn = 'flex h-11 w-11 items-center justify-center rounded-full bg-white/15 text-xl text-white backdrop-blur hover:bg-white/25 focus:outline-none focus-visible:ring-2 focus-visible:ring-white disabled:opacity-30';

  return (
    <div role="dialog" aria-modal="true" aria-label={t(language, 'crmImagesViewer')} className="fixed inset-0 z-[1000] flex flex-col bg-black text-white" style={{ paddingTop: 'env(safe-area-inset-top, 0px)', paddingBottom: 'env(safe-area-inset-bottom, 0px)' }}>
      <div className="flex items-center justify-between gap-3 px-3 py-2">
        <div className="min-w-0 text-sm">
          <div className="truncate font-medium">{img.label}</div>
          <div className="truncate text-xs text-white/70 tabular-nums">{img.at} · {index + 1} / {images.length}</div>
        </div>
        <div className="flex shrink-0 gap-2">
          <a className={btn} href={img.url} target="_blank" rel="noreferrer" aria-label={t(language, 'crmImagesOpenOriginal')} title={t(language, 'crmImagesOpenOriginal')}>↗</a>
          <button className={btn} onClick={() => zoomAt(viewRef.current.s > 1.01 ? 1 : 3, window.innerWidth / 2, window.innerHeight / 2)} aria-label={t(language, 'crmImagesZoom')} title={t(language, 'crmImagesZoom')}>{view.s > 1.01 ? '−' : '+'}</button>
          <button className={btn} onClick={onClose} aria-label={t(language, 'crmImagesClose')} autoFocus>✕</button>
        </div>
      </div>
      <div
        ref={box}
        className="relative min-h-0 flex-1 touch-none select-none overflow-hidden"
        onPointerDown={down} onPointerMove={move} onPointerUp={up} onPointerCancel={up}
        style={{ cursor: view.s > 1.01 ? 'grab' : 'zoom-in' }}
      >
        {!loaded && <div className="absolute inset-0 flex items-center justify-center text-sm text-white/60">{t(language, 'crmImagesLoading')}</div>}
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img
          key={img.url}
          src={img.url}
          alt={img.label}
          draggable={false}
          onLoad={() => setLoaded(true)}
          className="absolute inset-0 h-full w-full object-contain"
          style={{ transform: `translate(${view.x}px, ${view.y}px) scale(${view.s})`, transition: pointers.current.size ? 'none' : 'transform 120ms ease-out', opacity: loaded ? 1 : 0 }}
        />
        {images.length > 1 && (
          <>
            <button className={`${btn} absolute left-2 top-1/2 hidden -translate-y-1/2 sm:flex`} disabled={index === 0} onClick={(e) => { e.stopPropagation(); go(index - 1); }} onPointerDown={(e) => e.stopPropagation()} aria-label={t(language, 'crmImagesPrev')}>‹</button>
            <button className={`${btn} absolute right-2 top-1/2 hidden -translate-y-1/2 sm:flex`} disabled={index === images.length - 1} onClick={(e) => { e.stopPropagation(); go(index + 1); }} onPointerDown={(e) => e.stopPropagation()} aria-label={t(language, 'crmImagesNext')}>›</button>
          </>
        )}
      </div>
      {images.length > 1 && (
        <div className="flex gap-2 overflow-x-auto px-3 py-2" role="tablist">
          {images.map((m, i) => (
            // eslint-disable-next-line @next/next/no-img-element
            <button key={m.url} onClick={() => go(i)} aria-label={`${i + 1} / ${images.length}`} aria-current={i === index} className={`h-12 w-12 shrink-0 overflow-hidden rounded-md ring-2 ${i === index ? 'ring-white' : 'ring-transparent opacity-60'}`}>
              <img src={m.url} alt="" loading="lazy" className="h-full w-full object-cover" />
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
