'use client';

import { useEffect, useRef, useState } from 'react';

type ConversationMenuVariant = 'row' | 'header';

// Rough menu height (3 items + vertical padding) — only used for the upward-flip decision below;
// being a few pixels off either way doesn't matter since nothing else depends on it.
const ESTIMATED_MENU_HEIGHT = 152;
const MENU_WIDTH = 208;

interface MenuPosition {
  left: number;
  top?: number;
  bottom?: number;
}

/**
 * The "⋮" overflow menu behind mark-unread/copy-number/block-number, shared by the conversation
 * list row and the thread header — see MessagesView.tsx. The first hand-rolled dropdown in this
 * app (no headless-ui/radix/floating-ui dependency here), so a few things normally free from a
 * library are handled by hand:
 *
 * - Positioned with `position: fixed`, computed from the trigger button's own
 *   getBoundingClientRect() rather than a plain CSS `absolute` — the row variant lives inside the
 *   conversation list's `overflow-y-auto` container, which would otherwise clip a dropdown that
 *   extends past its visible scrolled area.
 * - Flips to open upward when there isn't room below the button (e.g. the last row in a long
 *   conversation list).
 * - Closes on outside pointerdown, Escape, or scrolling any ancestor (capture-phase listener, so
 *   it also catches the conversation list's own internal scroll) — without the last one, scrolling
 *   while the menu is open would leave it visually "detached" from the row it belongs to.
 */
export default function ConversationMenu({
  phoneNumber,
  blocked,
  onMarkUnread,
  onToggleBlock,
  variant,
}: {
  phoneNumber: string;
  blocked: boolean;
  onMarkUnread: () => void;
  onToggleBlock: () => void;
  variant: ConversationMenuVariant;
}) {
  const [open, setOpen] = useState(false);
  const [position, setPosition] = useState<MenuPosition | null>(null);
  const [copied, setCopied] = useState(false);
  const buttonRef = useRef<HTMLButtonElement>(null);
  const menuRef = useRef<HTMLDivElement>(null);

  function openMenu() {
    const rect = buttonRef.current?.getBoundingClientRect();
    if (!rect) return;
    const openUpward =
      window.innerHeight - rect.bottom < ESTIMATED_MENU_HEIGHT && rect.top > ESTIMATED_MENU_HEIGHT;
    const left = Math.max(8, Math.min(rect.right - MENU_WIDTH, window.innerWidth - MENU_WIDTH - 8));
    setPosition(
      openUpward
        ? { left, bottom: window.innerHeight - rect.top + 4 }
        : { left, top: rect.bottom + 4 },
    );
    setOpen(true);
  }

  useEffect(() => {
    if (!open) return;
    function handlePointerDown(e: MouseEvent) {
      const target = e.target as Node;
      if (menuRef.current?.contains(target) || buttonRef.current?.contains(target)) return;
      setOpen(false);
    }
    function handleKeyDown(e: KeyboardEvent) {
      if (e.key === 'Escape') setOpen(false);
    }
    function handleScroll() {
      setOpen(false);
    }
    document.addEventListener('mousedown', handlePointerDown);
    document.addEventListener('keydown', handleKeyDown);
    window.addEventListener('scroll', handleScroll, true);
    return () => {
      document.removeEventListener('mousedown', handlePointerDown);
      document.removeEventListener('keydown', handleKeyDown);
      window.removeEventListener('scroll', handleScroll, true);
    };
  }, [open]);

  function choose(action: () => void) {
    setOpen(false);
    action();
  }

  async function copyNumber() {
    try {
      await navigator.clipboard.writeText(phoneNumber);
      setCopied(true);
      setTimeout(() => {
        setCopied(false);
        setOpen(false);
      }, 900);
    } catch {
      // Clipboard blocked (e.g. insecure context) — nothing more to do.
      setOpen(false);
    }
  }

  const buttonSizeClass = variant === 'header' ? 'h-11 w-11' : 'h-6 w-6';
  const iconSize = variant === 'header' ? 20 : 15;

  return (
    <>
      <button
        ref={buttonRef}
        type="button"
        data-testid="conversation-menu-button"
        onClick={(e) => {
          e.stopPropagation();
          if (open) {
            setOpen(false);
          } else {
            openMenu();
          }
        }}
        aria-label="More options"
        aria-haspopup="menu"
        aria-expanded={open}
        className={`flex ${buttonSizeClass} shrink-0 items-center justify-center rounded-full text-zinc-400 hover:bg-zinc-200 hover:text-zinc-600`}
      >
        <svg width={iconSize} height={iconSize} viewBox="0 0 24 24" fill="currentColor" aria-hidden>
          <circle cx="12" cy="5" r="1.75" />
          <circle cx="12" cy="12" r="1.75" />
          <circle cx="12" cy="19" r="1.75" />
        </svg>
      </button>
      {open && position && (
        <div
          ref={menuRef}
          role="menu"
          data-testid="conversation-menu-dropdown"
          onClick={(e) => e.stopPropagation()}
          style={{ position: 'fixed', left: position.left, top: position.top, bottom: position.bottom, width: MENU_WIDTH }}
          className="z-50 overflow-hidden rounded-lg border border-zinc-200 bg-white py-1 shadow-lg"
        >
          <button
            type="button"
            role="menuitem"
            data-testid="conversation-menu-mark-unread"
            onClick={() => choose(onMarkUnread)}
            className="flex w-full items-center gap-2.5 px-3 py-2.5 text-left text-sm text-zinc-700 hover:bg-zinc-50"
          >
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden className="shrink-0 text-zinc-400">
              <rect width="20" height="16" x="2" y="4" rx="2" />
              <path d="m2 7 8.97 5.7a2 2 0 0 0 2.06 0L22 7" />
            </svg>
            Mark as unread
          </button>
          <button
            type="button"
            role="menuitem"
            data-testid="conversation-menu-copy-number"
            onClick={() => void copyNumber()}
            className="flex w-full items-center gap-2.5 px-3 py-2.5 text-left text-sm text-zinc-700 hover:bg-zinc-50"
          >
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden className="shrink-0 text-zinc-400">
              <rect width="14" height="14" x="8" y="8" rx="2" />
              <path d="M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2" />
            </svg>
            {copied ? 'Copied ✓' : 'Copy number'}
          </button>
          <button
            type="button"
            role="menuitem"
            data-testid="conversation-menu-block"
            onClick={() => choose(onToggleBlock)}
            className={`flex w-full items-center gap-2.5 px-3 py-2.5 text-left text-sm hover:bg-zinc-50 ${blocked ? 'text-zinc-700' : 'text-red-600'}`}
          >
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden className="shrink-0">
              <circle cx="12" cy="12" r="10" />
              <path d="m4.9 4.9 14.2 14.2" />
            </svg>
            {blocked ? 'Unblock number' : 'Block number'}
          </button>
        </div>
      )}
    </>
  );
}
